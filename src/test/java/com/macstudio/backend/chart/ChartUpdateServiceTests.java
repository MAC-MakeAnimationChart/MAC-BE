package com.macstudio.backend.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstudio.backend.chart.dto.ChartPatchCommand;
import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.chart.service.ChartUpdateService;
import com.macstudio.backend.chart.service.ChartUpdateValidator;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class ChartUpdateServiceTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private ChartRepository charts;
    private DatasetRepository datasets;
    private ChartThumbnailStorage thumbnails;
    private ChartUpdateService service;
    private Chart chart;

    @BeforeEach
    void setUp() {
        charts = mock(ChartRepository.class);
        datasets = mock(DatasetRepository.class);
        thumbnails = mock(ChartThumbnailStorage.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        Member member = Member.createEmailMember("chart-owner@example.com", "encoded-password");
        ReflectionTestUtils.setField(member, "id", 7L);
        Dataset dataset = Dataset.create(member, UUID.randomUUID(), "sales", 1,
                "{\"headers\":[\"month\",\"sales\"],\"rows\":[]}", 45, 0, 2);
        ReflectionTestUtils.setField(dataset, "id", 11L);
        ReflectionTestUtils.setField(dataset, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        ReflectionTestUtils.setField(dataset, "updatedAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        chart = Chart.create(dataset, "Old title", "BAR", 1, "{\"x\":\"month\",\"y\":\"sales\"}");
        ReflectionTestUtils.setField(chart, "id", 21L);
        ReflectionTestUtils.setField(chart, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        ReflectionTestUtils.setField(chart, "updatedAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        chart.attachThumbnail("dev/chart-thumbnails/21/old.png", 100, "image/png", 1L);

        when(charts.findOwnedById(21L, 7L)).thenReturn(Optional.of(chart));
        when(charts.findOwnedByIdForUpdate(21L, 7L)).thenReturn(Optional.of(chart));
        when(datasets.calculateStorageBytes(7L)).thenReturn(500L);
        service = new ChartUpdateService(charts, datasets, new ChartUpdateValidator(),
                thumbnails, manager, 30 * 1024 * 1024L);
    }

    @Test
    void updatesSettingsAndInvalidatesPreviousThumbnailBestEffort() throws Exception {
        when(thumbnails.deleteBestEffort("dev/chart-thumbnails/21/old.png")).thenReturn(false);
        var command = new ChartPatchCommand("1", "New title", null, 1,
                json.readTree("{\"x\":\"month\",\"y\":\"sales\",\"color\":\"blue\"}"));

        var response = service.patch("7", "21", command);

        assertThat(response.chartRevision()).isEqualTo("2");
        assertThat(response.datasetRevision()).isEqualTo("1");
        assertThat(response.thumbnailState()).isEqualTo("NONE");
        assertThat(chart.getTitle()).isEqualTo("New title");
        assertThat(chart.getThumbnailKey()).isNull();
        verify(thumbnails).deleteBestEffort("dev/chart-thumbnails/21/old.png");
    }

    @Test
    void noOpSaveKeepsRevisionAndThumbnail() {
        var response = service.patch("7", "21", new ChartPatchCommand("1", null, null, null, null));

        assertThat(response.chartRevision()).isEqualTo("1");
        assertThat(response.thumbnailState()).isEqualTo("READY");
        assertThat(chart.getThumbnailChartRevision()).isEqualTo(1L);
        assertThat(chart.getThumbnailDatasetRevision()).isEqualTo(1L);
    }

    @Test
    void rejectsStaleRevisionBeforeUploadingThumbnail() {
        var command = new ChartPatchCommand("9", "New title", null, null, null);

        assertThatThrownBy(() -> service.patch("7", "21", command))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CHART_REVISION_CONFLICT"));
    }
}
