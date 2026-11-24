package com.macstudio.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.storage.service.S3ConnectivityService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3ConnectivityServiceTests {
    @Mock
    private S3Client s3Client;
    private AutoCloseable mocks;
    private S3ConnectivityService service;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        service = new S3ConnectivityService(s3Client, "test-bucket", 1024 * 1024);
    }

    @org.junit.jupiter.api.AfterEach
    void closeMocks() throws Exception {
        mocks.close();
    }

    @Test
    void uploadsServerNamedPngAndVerifiesStoredObject() throws Exception {
        byte[] png = png();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("put-etag").build());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength((long) png.length)
                        .eTag("head-etag").build());

        var result = service.uploadAndVerify(new MockMultipartFile(
                "image", "chart.png", "image/png", png));

        assertThat(result.verified()).isTrue();
        assertThat(result.objectKey()).startsWith("dev/connectivity-check/").endsWith(".png");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.sizeBytes()).isEqualTo(png.length);
        assertThat(result.eTag()).isEqualTo("head-etag");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(request.getValue().key()).isEqualTo(result.objectKey());
        assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        verify(s3Client).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void rejectsNonImageBeforeCallingS3() {
        var file = new MockMultipartFile("image", "fake.png", "image/png", "not-image".getBytes());

        assertThatThrownBy(() -> service.uploadAndVerify(file))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("UNSUPPORTED_IMAGE"));
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void rejectsOversizedImageBeforeCallingS3() {
        service = new S3ConnectivityService(s3Client, "test-bucket", 3);
        var file = new MockMultipartFile("image", "large.png", "image/png", new byte[4]);

        assertThatThrownBy(() -> service.uploadAndVerify(file))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IMAGE_TOO_LARGE"));
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
