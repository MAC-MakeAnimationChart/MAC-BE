package com.macstudio.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.macstudio.backend.storage.service.ChartThumbnailStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** private S3 썸네일 객체의 서버 경유 다운로드를 검증한다. */
class ChartThumbnailStorageTests {
    @Test
    void downloadsOnlyServerManagedThumbnailKey() {
        @SuppressWarnings("unchecked")
        ObjectProvider<S3Client> clients = mock(ObjectProvider.class);
        S3Client s3 = mock(S3Client.class);
        when(clients.getIfAvailable()).thenReturn(s3);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[]{1, 2, 3}));
        var storage = new ChartThumbnailStorage(clients, "bucket", "dev/chart-thumbnails/", 1024L);

        byte[] result = storage.download("dev/chart-thumbnails/21/current.png");

        assertThat(result).containsExactly(1, 2, 3);
    }
}
