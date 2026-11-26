package com.macstudio.backend.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstudio.backend.chart.dto.ChartCreateCommand;
import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.chart.service.ChartCreationService;
import com.macstudio.backend.chart.service.ChartCreationValidator;
import com.macstudio.backend.chart.service.ChartUpdateValidator;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/** 기존 데이터셋 차트 추가의 소유권·revision·저장량 경계를 검증한다. */
class ChartCreationServiceTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private DatasetRepository datasets;
    private ChartRepository charts;
    private ChartCreationService service;
    private Dataset dataset;

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRepository.class);
        charts = mock(ChartRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        Member member = Member.createEmailMember("chart-owner@example.com", "encoded-password");
        ReflectionTestUtils.setField(member, "id", 7L);
        dataset = Dataset.create(member, UUID.randomUUID(), "sales", 1,
                "{\"headers\":[\"month\",\"sales\"],\"rows\":[]}", 45L, 0, 2);
        ReflectionTestUtils.setField(dataset, "id", 11L);
        ReflectionTestUtils.setField(dataset, "createdAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        ReflectionTestUtils.setField(dataset, "updatedAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        when(datasets.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(dataset));
        when(datasets.calculateStorageBytes(7L)).thenReturn(500L);
        when(charts.saveAndFlush(any(Chart.class))).thenAnswer(invocation -> {
            Chart value = invocation.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 22L);
            ReflectionTestUtils.setField(value, "createdAt", LocalDateTime.of(2026, 11, 26, 11, 0));
            ReflectionTestUtils.setField(value, "updatedAt", LocalDateTime.of(2026, 11, 26, 11, 0));
            return value;
        });
        service = new ChartCreationService(datasets, charts,
                new ChartCreationValidator(new ChartUpdateValidator()), manager, 30L * 1024 * 1024);
    }

    @Test
    void createsRevisionOneChartWithoutChangingDatasetRevision() throws Exception {
        var response = service.create("7", "11", command("1"));

        assertThat(response.datasetId()).isEqualTo("11");
        assertThat(response.datasetRevision()).isEqualTo("1");
        assertThat(response.chartId()).isEqualTo("22");
        assertThat(response.chartRevision()).isEqualTo("1");
        var captured = org.mockito.ArgumentCaptor.forClass(Chart.class);
        verify(charts).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getDataset()).isSameAs(dataset);
        assertThat(captured.getValue().getFigureJson()).contains("month", "sales");
    }

    @Test
    void rejectsStaleDatasetRevisionBeforeCreatingChart() throws Exception {
        assertThatThrownBy(() -> service.create("7", "11", command("2")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DATASET_REVISION_CONFLICT"));
        verify(charts, never()).saveAndFlush(any());
    }

    @Test
    void hidesMissingOrOtherMembersDataset() throws Exception {
        when(datasets.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("7", "11", command("1")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DATASET_NOT_FOUND"));
    }

    @Test
    void rejectsCreationBeyondMemberStorageLimit() throws Exception {
        when(datasets.calculateStorageBytes(7L)).thenReturn(30L * 1024 * 1024);

        assertThatThrownBy(() -> service.create("7", "11", command("1")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("STORAGE_LIMIT_EXCEEDED"));
        verify(charts, never()).saveAndFlush(any());
    }

    private ChartCreateCommand command(String revision) throws Exception {
        return new ChartCreateCommand(revision, "Monthly sales", "BAR", 1,
                json.readTree("{\"x\":\"month\",\"y\":\"sales\"}"));
    }
}
