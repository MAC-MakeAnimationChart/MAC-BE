package com.macstudio.backend.storage.service;

import com.macstudio.backend.chart.repository.ChartRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/** DB가 참조하지 않는 오래된 차트 썸네일만 제한된 수로 정리한다. */
@Service
@ConditionalOnProperty(name = {"app.storage.enabled", "app.storage.orphan-cleanup.enabled"},
        havingValue = "true")
public class OrphanThumbnailCleanupService {
    private final S3Client s3;
    private final ChartRepository charts;
    private final String bucket;
    private final String prefix;

    public OrphanThumbnailCleanupService(S3Client s3, ChartRepository charts,
            @Value("${app.storage.bucket}") String bucket,
            @Value("${app.storage.thumbnail-prefix:dev/chart-thumbnails/}") String prefix) {
        this.s3 = s3;
        this.charts = charts;
        this.bucket = bucket;
        this.prefix = prefix.endsWith("/") ? prefix : prefix + "/";
    }

    public CleanupResult cleanup(int scanLimit, int deleteLimit, Duration grace) {
        if (scanLimit < 1 || scanLimit > 100_000 || deleteLimit < 1 || deleteLimit > scanLimit
                || grace.isNegative() || grace.isZero()) {
            throw new IllegalArgumentException("Invalid orphan thumbnail cleanup limits");
        }
        Instant cutoff = Instant.now().minus(grace);
        var referenced = new HashSet<>(charts.findAllThumbnailKeys());
        int scanned = 0;
        int deleted = 0;
        int failed = 0;
        String token = null;
        try {
            do {
                int pageSize = Math.min(1000, scanLimit - scanned);
                var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(prefix).maxKeys(pageSize)
                        .continuationToken(token).build());
                for (var object : response.contents()) {
                    scanned++;
                    if (deleted >= deleteLimit || referenced.contains(object.key())
                            || object.lastModified() == null || !object.lastModified().isBefore(cutoff)) {
                        continue;
                    }
                    // 목록 조회 뒤 새 참조가 생긴 경우를 막기 위해 삭제 직전에 다시 확인한다.
                    if (charts.existsByThumbnailKey(object.key())) continue;
                    try {
                        s3.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket).key(object.key()).build());
                        deleted++;
                    } catch (SdkException exception) {
                        failed++;
                    }
                }
                token = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (token != null && scanned < scanLimit && deleted < deleteLimit);
        } catch (SdkException exception) {
            failed++;
        }
        return new CleanupResult(scanned, deleted, failed);
    }

    public record CleanupResult(int scanned, int deleted, int failed) {}
}
