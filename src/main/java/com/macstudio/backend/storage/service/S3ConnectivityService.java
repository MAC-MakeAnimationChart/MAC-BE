package com.macstudio.backend.storage.service;

import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.storage.dto.S3ConnectivityUploadResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** 로컬 Swagger에서 private S3의 PutObject와 HeadObject 연결을 점검한다. */
@Service
@Profile("local")
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true")
public class S3ConnectivityService {
    private static final String TEST_PREFIX = "dev/connectivity-check/";

    private final S3Client s3Client;
    private final String bucket;
    private final long maxUploadBytes;

    public S3ConnectivityService(S3Client s3Client,
            @Value("${app.storage.bucket}") String bucket,
            @Value("${app.limits.member-upload-bytes}") long maxUploadBytes) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.maxUploadBytes = maxUploadBytes;
    }

    /** 실제 이미지 바이트를 검증하고 서버 생성 dev 키에 업로드한 뒤 HEAD 결과를 확인한다. */
    public S3ConnectivityUploadResponse uploadAndVerify(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw badRequest("EMPTY_IMAGE", "업로드할 PNG 또는 JPEG 이미지를 선택해 주세요.");
        }
        if (image.getSize() > maxUploadBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE",
                    "이미지 크기가 허용 범위를 초과했습니다.");
        }

        byte[] bytes = readBytes(image);
        DetectedImage detected = detectImage(bytes);
        String key = TEST_PREFIX + UUID.randomUUID() + detected.extension();

        try {
            PutObjectResponse uploaded = s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(detected.contentType())
                            .contentLength((long) bytes.length)
                            .serverSideEncryption(ServerSideEncryption.AES256)
                            .build(),
                    RequestBody.fromBytes(bytes));
            HeadObjectResponse stored = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            if (stored.contentLength() == null || stored.contentLength() != bytes.length) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "S3_VERIFICATION_FAILED",
                        "S3 객체 크기 확인에 실패했습니다.");
            }
            String eTag = stored.eTag() != null ? stored.eTag() : uploaded.eTag();
            return new S3ConnectivityUploadResponse(
                    key, detected.contentType(), stored.contentLength(), eTag, true);
        } catch (ApiException exception) {
            throw exception;
        } catch (SdkException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "S3_UPLOAD_FAILED",
                    "S3 연결 또는 이미지 업로드에 실패했습니다.");
        }
    }

    private byte[] readBytes(MultipartFile image) {
        try {
            return image.getBytes();
        } catch (IOException exception) {
            throw badRequest("INVALID_IMAGE", "이미지 파일을 읽을 수 없습니다.");
        }
    }

    private DetectedImage detectImage(byte[] bytes) {
        String contentType;
        String extension;
        if (isPng(bytes)) {
            contentType = "image/png";
            extension = ".png";
        } else if (isJpeg(bytes)) {
            contentType = "image/jpeg";
            extension = ".jpg";
        } else {
            throw badRequest("UNSUPPORTED_IMAGE", "PNG 또는 JPEG 이미지만 업로드할 수 있습니다.");
        }

        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
                throw badRequest("INVALID_IMAGE", "올바른 이미지 파일이 아닙니다.");
            }
        } catch (IOException exception) {
            throw badRequest("INVALID_IMAGE", "올바른 이미지 파일이 아닙니다.");
        }
        return new DetectedImage(contentType.toLowerCase(Locale.ROOT), extension);
    }

    private static boolean isPng(byte[] value) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (value.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (value[i] != signature[i]) return false;
        }
        return true;
    }

    private static boolean isJpeg(byte[] value) {
        return value.length >= 3
                && value[0] == (byte) 0xff
                && value[1] == (byte) 0xd8
                && value[2] == (byte) 0xff;
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record DetectedImage(String contentType, String extension) {}
}
