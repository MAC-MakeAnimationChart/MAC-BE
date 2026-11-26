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
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.dataset.service.DatasetChartQueryService;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** 선택 데이터셋 소유권과 독립 차트 페이지·썸네일 상태 조립을 검증한다. */
class DatasetChartQueryServiceTests {
    private DatasetRepository datasets;
    private ChartRepository charts;
    private DatasetChartQueryService service;
    private Dataset dataset;

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRepository.class);
        charts = mock(ChartRepository.class);
        Member member = Member.createEmailMember("charts@example.com", "encoded-password");
        ReflectionTestUtils.setField(member, "id", 7L);
        dataset = Dataset.create(member, UUID.randomUUID(), "sales", 1,
                "{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\",10]]}",
                60L, 1, 2);
        ReflectionTestUtils.setField(dataset, "id", 11L);
        ReflectionTestUtils.setField(dataset, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        ReflectionTestUtils.setField(dataset, "updatedAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        service = new DatasetChartQueryService(datasets, charts);
    }

    @Test
    void returnsSelectedDatasetAndIndependentChartPage() {
        Chart ready = chart(21L, "Monthly sales", "BAR");
        ready.attachThumbnail("dev/chart-thumbnails/21/current.png", 100L, "image/png", 1L);
        Chart empty = chart(22L, "Sales share", "PIE");
        when(datasets.findOwnedById(11L, 7L)).thenReturn(Optional.of(dataset));
        when(charts.findByDataset_Id(org.mockito.ArgumentMatchers.eq(11L), any())).thenReturn(
                new PageImpl<>(List.of(ready, empty), PageRequest.of(0, 20), 41));

        var response = service.get("7", "11", 0, 20);

        assertThat(response.dataset().datasetId()).isEqualTo("11");
        assertThat(response.dataset().chartCount()).isEqualTo(41L);
        assertThat(response.charts()).hasSize(2);
        assertThat(response.charts().get(0).thumbnailState()).isEqualTo("READY");
        assertThat(response.charts().get(0).thumbnailUrl())
                .isEqualTo("/api/v1/charts/21/thumbnail");
        assertThat(response.charts().get(1).thumbnailState()).isEqualTo("NONE");
        assertThat(response.page().totalPages()).isEqualTo(3);
        assertThat(response.page().hasNext()).isTrue();
    }

    @Test
    void hidesAnotherMembersDatasetAsNotFound() {
        when(datasets.findOwnedById(11L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("7", "11", 0, 20))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("DATASET_NOT_FOUND");
                });
        verify(charts, never()).findByDataset_Id(any(), any());
    }

    @Test
    void rejectsUnsafeChartPageBeforeQueryingDatabase() {
        assertThatThrownBy(() -> service.get("7", "11", 0, 51))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_PAGE"));
        verify(datasets, never()).findOwnedById(any(), any());
    }

    private Chart chart(long id, String title, String type) {
        Chart value = Chart.create(dataset, title, type, 1, "{\"x\":\"month\",\"y\":\"sales\"}");
        ReflectionTestUtils.setField(value, "id", id);
        ReflectionTestUtils.setField(value, "createdAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        ReflectionTestUtils.setField(value, "updatedAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        return value;
    }
}
