package com.macstudio.backend.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.dto.DatasetPatchCommand;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.dataset.service.DatasetUpdateService;
import com.macstudio.backend.dataset.service.DatasetUpdateValidator;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.storage.service.ChartThumbnailStorage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class DatasetUpdateServiceTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private DatasetRepository datasets;
    private ChartRepository charts;
    private ChartThumbnailStorage thumbnails;
    private DatasetUpdateService service;
    private Dataset dataset;
    private Chart first;
    private Chart second;

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRepository.class);
        charts = mock(ChartRepository.class);
        thumbnails = mock(ChartThumbnailStorage.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        Member member = Member.createEmailMember("dataset-owner@example.com", "encoded-password");
        ReflectionTestUtils.setField(member, "id", 7L);
        dataset = Dataset.create(member, UUID.randomUUID(), "sales", 1,
                "{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\",10]]}", 60, 1, 2);
        ReflectionTestUtils.setField(dataset, "id", 11L);
        ReflectionTestUtils.setField(dataset, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        ReflectionTestUtils.setField(dataset, "updatedAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        first = chart(21L, "dev/chart-thumbnails/21/old.png", 100);
        second = chart(22L, "dev/chart-thumbnails/22/old.png", 120);

        when(datasets.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(dataset));
        when(datasets.calculateStorageBytes(7L)).thenReturn(1_000L);
        when(charts.findAllByDatasetIdForUpdate(11L)).thenReturn(List.of(first, second));
        service = new DatasetUpdateService(datasets, charts, new DatasetUpdateValidator(),
                thumbnails, manager, 10 * 1024 * 1024L, 30 * 1024 * 1024L);
    }

    @Test
    void nameOnlyUpdateKeepsAllThumbnails() {
        var response = service.patch("7", "11",
                new DatasetPatchCommand("1", "renamed", null, null));

        assertThat(response.datasetRevision()).isEqualTo("2");
        assertThat(response.invalidatedThumbnailCount()).isZero();
        assertThat(dataset.getName()).isEqualTo("renamed");
        assertThat(first.getThumbnailKey()).isNotNull();
        verify(charts, never()).findAllByDatasetIdForUpdate(any());
        verify(thumbnails, never()).deleteBestEffort(any());
    }

    @Test
    void dataUpdateInvalidatesEveryChildThumbnailWithoutChangingChartRevision() throws Exception {
        var command = new DatasetPatchCommand("1", null, 1,
                json.readTree("{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\",20],[\"Feb\",30]]}"));

        var response = service.patch("7", "11", command);

        assertThat(response.datasetRevision()).isEqualTo("2");
        assertThat(response.invalidatedThumbnailCount()).isEqualTo(2);
        assertThat(dataset.getRowCount()).isEqualTo(2);
        assertThat(first.getRevision()).isEqualTo(1L);
        assertThat(second.getRevision()).isEqualTo(1L);
        assertThat(first.getThumbnailKey()).isNull();
        assertThat(second.getThumbnailKey()).isNull();
        verify(thumbnails).deleteBestEffort("dev/chart-thumbnails/21/old.png");
        verify(thumbnails).deleteBestEffort("dev/chart-thumbnails/22/old.png");
    }

    @Test
    void staleRevisionDoesNotModifyDatasetOrThumbnails() {
        var command = new DatasetPatchCommand("9", "renamed", null, null);

        assertThatThrownBy(() -> service.patch("7", "11", command))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DATASET_REVISION_CONFLICT"));
        assertThat(dataset.getRevision()).isEqualTo(1L);
        verify(charts, never()).findAllByDatasetIdForUpdate(any());
    }

    @Test
    void rejectsMismatchedRowsBeforeOpeningUpdateTransaction() throws Exception {
        var invalid = new DatasetPatchCommand("1", null, 1,
                json.readTree("{\"headers\":[\"a\",\"b\"],\"rows\":[[1]]}"));

        assertThatThrownBy(() -> service.patch("7", "11", invalid))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_DATA_SCHEMA"));
        verify(datasets, never()).findOwnedByIdForUpdate(any(), any());
    }

    private Chart chart(long id, String key, long size) {
        Chart value = Chart.create(dataset, "Chart " + id, "BAR", 1, "{\"x\":\"month\"}");
        ReflectionTestUtils.setField(value, "id", id);
        ReflectionTestUtils.setField(value, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        ReflectionTestUtils.setField(value, "updatedAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        value.attachThumbnail(key, size, "image/png", value.getDataset().getRevision());
        return value;
    }
}
