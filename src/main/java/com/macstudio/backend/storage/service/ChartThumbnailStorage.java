package com.macstudio.backend.storage.service;

import com.macstudio.backend.global.exception.ApiException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** 차트 썸네일을 서버 생성 키로 업로드하고 미참조 객체를 안전하게 삭제한다. */
@Service
public class ChartThumbnailStorage {
    private static final Logger log = LoggerFactory.getLogger(ChartThumbnailStorage.class);
    private final ObjectProvider<S3Client> clients;
    private final String bucket;
    private final String prefix;
    private final long maxBytes;

    public ChartThumbnailStorage(ObjectProvider<S3Client> clients,
            @Value("${app.storage.bucket:}") String bucket,
            @Value("${app.storage.thumbnail-prefix:dev/chart-thumbnails/}") String prefix,
            @Value("${app.limits.thumbnail-bytes:${app.limits.member-upload-bytes}}") long maxBytes) {
        this.clients = clients;
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        this.maxBytes = maxBytes;
    }

    public StoredThumbnail upload(long chartId, MultipartFile image) {
        S3Client client = requireClient();
        var validated = ImageFileValidator.validate(image, maxBytes);
        String key = prefix + chartId + "/" + UUID.randomUUID() + validated.extension();
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType(validated.contentType())
                            .contentLength((long) validated.bytes().length)
                            .serverSideEncryption(ServerSideEncryption.AES256).build(),
                    RequestBody.fromBytes(validated.bytes()));
            return new StoredThumbnail(key, validated.bytes().length, validated.contentType());
        } catch (SdkException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "THUMBNAIL_UPLOAD_FAILED",
                    "썸네일 업로드에 실패했습니다.");
        }
    }

    /** 삭제 실패는 저장 성공을 되돌리지 않고 정리 스케줄러에 맡긴다. */
    public boolean deleteBestEffort(String key) {
        if (key == null || key.isBlank()) return true;
        S3Client client = clients.getIfAvailable();
        if (client == null || bucket.isBlank() || !key.startsWith(prefix)) return false;
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (SdkException exception) {
            log.warn("Chart thumbnail cleanup deferred after S3 delete failure");
            return false;
        }
    }

    /** 소유권 검사가 끝난 현재 썸네일 키의 private S3 객체를 바이트로 읽는다. */
    public byte[] download(String key) {
        if (key == null || !key.startsWith(prefix)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "THUMBNAIL_NOT_FOUND",
                    "현재 썸네일을 찾을 수 없습니다.");
        }
        try {
            return requireClient().getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "THUMBNAIL_NOT_FOUND",
                        "현재 썸네일을 찾을 수 없습니다.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "THUMBNAIL_READ_FAILED",
                    "썸네일을 불러오지 못했습니다.");
        } catch (SdkException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "THUMBNAIL_READ_FAILED",
                    "썸네일을 불러오지 못했습니다.");
        }
    }

    private S3Client requireClient() {
        S3Client client = clients.getIfAvailable();
        if (client == null || bucket.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "THUMBNAIL_STORAGE_UNAVAILABLE",
                    "썸네일 저장소를 사용할 수 없습니다.");
        }
        return client;
    }

    private static String normalizePrefix(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid thumbnail prefix");
        }
        return value.endsWith("/") ? value : value + "/";
    }

    public record StoredThumbnail(String key, long sizeBytes, String contentType) {}
}
