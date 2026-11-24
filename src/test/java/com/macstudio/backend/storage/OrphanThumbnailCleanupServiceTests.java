package com.macstudio.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.storage.service.OrphanThumbnailCleanupService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

class OrphanThumbnailCleanupServiceTests {
    @Test
    void deletesOnlyOldUnreferencedObjectsAndRechecksReference() {
        S3Client s3 = org.mockito.Mockito.mock(S3Client.class);
        ChartRepository charts = org.mockito.Mockito.mock(ChartRepository.class);
        String active = "dev/chart-thumbnails/1/active.png";
        String orphan = "dev/chart-thumbnails/2/orphan.png";
        String recent = "dev/chart-thumbnails/3/recent.png";
        when(charts.findAllThumbnailKeys()).thenReturn(List.of(active));
        when(charts.existsByThumbnailKey(orphan)).thenReturn(false);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .isTruncated(false)
                .contents(
                        S3Object.builder().key(active).lastModified(Instant.now().minus(Duration.ofDays(3))).build(),
                        S3Object.builder().key(orphan).lastModified(Instant.now().minus(Duration.ofDays(3))).build(),
                        S3Object.builder().key(recent).lastModified(Instant.now()).build())
                .build());
        var service = new OrphanThumbnailCleanupService(s3, charts, "bucket", "dev/chart-thumbnails/");

        var result = service.cleanup(100, 10, Duration.ofHours(24));

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.deleted()).isEqualTo(1);
        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).isEqualTo(orphan);
        verify(charts).existsByThumbnailKey(orphan);
        verify(charts, never()).existsByThumbnailKey(active);
    }
}
