package com.macstudio.backend.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstudio.backend.chart.dto.ChartThumbnailSaveCommand;
import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.chart.service.ChartThumbnailService;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.storage.service.ChartThumbnailStorage;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** 전용 썸네일 저장의 revision 경쟁·교체·용량 실패 정리를 검증한다. */
class ChartThumbnailServiceTests {
    private ChartRepository charts;
    private DatasetRepository datasets;
    private ChartThumbnailStorage thumbnails;
    private ChartThumbnailService service;
    private Chart chart;
    private MockMultipartFile image;

    @BeforeEach
    void setUp() {
        charts = mock(ChartRepository.class);
        datasets = mock(DatasetRepository.class);
        thumbnails = mock(ChartThumbnailStorage.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        chart = chartFixture(1L);
        chart.attachThumbnail("dev/chart-thumbnails/21/old.png", 100L, "image/png", 1L);
        when(charts.findOwnedById(21L, 7L)).thenReturn(Optional.of(chart));
        when(charts.findOwnedByIdForUpdate(21L, 7L)).thenReturn(Optional.of(chart));
        when(datasets.calculateStorageBytes(7L)).thenReturn(500L);
        when(thumbnails.upload(org.mockito.ArgumentMatchers.eq(21L), any())).thenReturn(
                new ChartThumbnailStorage.StoredThumbnail(
                        "dev/chart-thumbnails/21/new.png", 120L, "image/png"));
        service = new ChartThumbnailService(charts, datasets, thumbnails, manager,
                30L * 1024 * 1024);
        image = new MockMultipartFile("thumbnail", "chart.png", "image/png", new byte[]{1});
    }

    @Test
    void replacesThumbnailOnlyWhenBothRevisionsMatch() {
        var response = service.save("7", "21", new ChartThumbnailSaveCommand("1", "1"), image);

        assertThat(response.thumbnailState()).isEqualTo("READY");
        assertThat(response.datasetRevision()).isEqualTo("1");
        assertThat(response.chartRevision()).isEqualTo("1");
        assertThat(chart.getThumbnailKey()).isEqualTo("dev/chart-thumbnails/21/new.png");
        assertThat(chart.getThumbnailChartRevision()).isEqualTo(1L);
        assertThat(chart.getThumbnailDatasetRevision()).isEqualTo(1L);
        verify(thumbnails).deleteBestEffort("dev/chart-thumbnails/21/old.png");
    }

    @Test
    void rejectsStaleDatasetRevisionBeforeUploading() {
        assertThatThrownBy(() -> service.save("7", "21",
                new ChartThumbnailSaveCommand("2", "1"), image))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("THUMBNAIL_REVISION_CONFLICT"));

        verify(thumbnails, never()).upload(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void deletesUploadedObjectWhenRevisionChangesDuringUpload() {
        Chart changed = chartFixture(2L);
        when(charts.findOwnedByIdForUpdate(21L, 7L)).thenReturn(Optional.of(changed));

        assertThatThrownBy(() -> service.save("7", "21",
                new ChartThumbnailSaveCommand("1", "1"), image))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("THUMBNAIL_REVISION_CONFLICT"));

        verify(thumbnails).deleteBestEffort("dev/chart-thumbnails/21/new.png");
    }

    @Test
    void deletesUploadedObjectWhenMemberStorageLimitIsExceeded() {
        when(datasets.calculateStorageBytes(7L)).thenReturn(30L * 1024 * 1024);

        assertThatThrownBy(() -> service.save("7", "21",
                new ChartThumbnailSaveCommand("1", "1"), image))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("STORAGE_LIMIT_EXCEEDED"));

        verify(thumbnails).deleteBestEffort("dev/chart-thumbnails/21/new.png");
    }

    private static Chart chartFixture(long datasetRevision) {
        Member member = Member.createEmailMember("chart-owner@example.com", "encoded-password");
        ReflectionTestUtils.setField(member, "id", 7L);
        Dataset dataset = Dataset.create(member, UUID.randomUUID(), "sales-" + datasetRevision, 1,
                "{\"headers\":[\"month\",\"sales\"],\"rows\":[]}", 45L, 0, 2);
        ReflectionTestUtils.setField(dataset, "id", 11L);
        ReflectionTestUtils.setField(dataset, "revision", datasetRevision);
        ReflectionTestUtils.setField(dataset, "createdAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        ReflectionTestUtils.setField(dataset, "updatedAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        Chart value = Chart.create(dataset, "Sales", "BAR", 1,
                "{\"x\":\"month\",\"y\":\"sales\"}");
        ReflectionTestUtils.setField(value, "id", 21L);
        ReflectionTestUtils.setField(value, "createdAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        ReflectionTestUtils.setField(value, "updatedAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        return value;
    }
}
