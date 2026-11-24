package com.macstudio.backend.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.dto.DatasetCreateCommand;
import com.macstudio.backend.dataset.dto.FirstChartCreateCommand;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.dataset.service.DatasetCommandValidator;
import com.macstudio.backend.dataset.service.DatasetCreationService;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class DatasetCreationServiceTests {
    private static final UUID REQUEST_ID = UUID.fromString("d9428888-122b-4dcf-a2a5-3f196618b885");
    private final JsonMapper json = JsonMapper.builder().build();
    private MemberRepository members;
    private DatasetRepository datasets;
    private ChartRepository charts;
    private DatasetCreationService service;
    private Member member;

    @BeforeEach
    void setUp() {
        members = mock(MemberRepository.class);
        datasets = mock(DatasetRepository.class);
        charts = mock(ChartRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        member = Member.createEmailMember("member@example.com", "encoded-password");
        ReflectionTestUtils.setField(member, "id", 7L);
        when(members.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
        when(datasets.findByMember_IdAndCreationRequestId(eq(7L), any(byte[].class)))
                .thenReturn(Optional.empty());
        when(datasets.calculateStorageBytes(7L)).thenReturn(0L);
        when(datasets.saveAndFlush(any(Dataset.class))).thenAnswer(invocation -> {
            Dataset value = invocation.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 11L);
            ReflectionTestUtils.setField(value, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
            ReflectionTestUtils.setField(value, "updatedAt", LocalDateTime.of(2026, 11, 24, 10, 0));
            return value;
        });
        when(charts.saveAndFlush(any(Chart.class))).thenAnswer(invocation -> {
            Chart value = invocation.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 21L);
            ReflectionTestUtils.setField(value, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
            ReflectionTestUtils.setField(value, "updatedAt", LocalDateTime.of(2026, 11, 24, 10, 0));
            return value;
        });
        service = new DatasetCreationService(members, datasets, charts,
                new DatasetCommandValidator(), manager, 10 * 1024 * 1024L, 30 * 1024 * 1024L);
    }

    @Test
    void createsDatasetAndFirstChartInOneUseCase() throws Exception {
        var response = service.create("7", REQUEST_ID.toString(), command("sales"));

        assertThat(response.datasetId()).isEqualTo("11");
        assertThat(response.chartId()).isEqualTo("21");
        assertThat(response.datasetRevision()).isEqualTo("1");
        assertThat(response.chartRevision()).isEqualTo("1");

        var dataset = org.mockito.ArgumentCaptor.forClass(Dataset.class);
        var chart = org.mockito.ArgumentCaptor.forClass(Chart.class);
        verify(datasets).saveAndFlush(dataset.capture());
        verify(charts).saveAndFlush(chart.capture());
        assertThat(dataset.getValue().getRowCount()).isEqualTo(2);
        assertThat(dataset.getValue().getColumnCount()).isEqualTo(2);
        assertThat(chart.getValue().getDataset()).isSameAs(dataset.getValue());
        assertThat(chart.getValue().getFigureJson()).contains("month", "sales");
    }

    @Test
    void rejectsReusingIdempotencyKeyWithDifferentCommand() throws Exception {
        Dataset existing = Dataset.create(member, REQUEST_ID, "old-name", 1,
                "{\"headers\":[\"x\"],\"rows\":[]}", 37, 0, 1);
        ReflectionTestUtils.setField(existing, "id", 11L);
        ReflectionTestUtils.setField(existing, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        Chart first = Chart.create(existing, "Chart", "BAR", 1, "{\"x\":\"x\"}");
        ReflectionTestUtils.setField(first, "id", 21L);
        when(datasets.findByMember_IdAndCreationRequestId(eq(7L), any(byte[].class)))
                .thenReturn(Optional.of(existing));
        when(charts.findFirstByDataset_IdOrderByIdAsc(11L)).thenReturn(Optional.of(first));

        assertThatThrownBy(() -> service.create("7", REQUEST_ID.toString(), command("new-name")))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verify(datasets, never()).saveAndFlush(any());
    }

    @Test
    void rejectsCreationBeyondMemberStorageLimit() throws Exception {
        when(datasets.calculateStorageBytes(7L)).thenReturn(30 * 1024 * 1024L);

        assertThatThrownBy(() -> service.create("7", REQUEST_ID.toString(), command("sales")))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STORAGE_LIMIT_EXCEEDED"));
        verify(datasets, never()).saveAndFlush(any());
    }

    private DatasetCreateCommand command(String name) throws Exception {
        return new DatasetCreateCommand(name, 1,
                json.readTree("{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\",10],[\"Feb\",20]]}"),
                new FirstChartCreateCommand("Chart", "BAR", 1,
                        json.readTree("{\"x\":\"month\",\"y\":\"sales\"}")));
    }
}
