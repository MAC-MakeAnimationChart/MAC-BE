package com.macstudio.backend.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.dto.DatasetCreateCommand;
import com.macstudio.backend.dataset.dto.FirstChartCreateCommand;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.dataset.service.DatasetCreationService;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberRepository;
import com.macstudio.backend.support.LocalMySqlTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Transactional
class DatasetCreationMySqlTests extends LocalMySqlTestSupport {
    @Autowired MemberRepository members;
    @Autowired DatasetRepository datasets;
    @Autowired ChartRepository charts;
    @Autowired DatasetCreationService service;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void atomicallyCreatesDatasetAndFirstChartAndReplaysSameRequest() throws Exception {
        Member member = members.saveAndFlush(Member.createEmailMember(
                "it-dataset-" + UUID.randomUUID() + "@example.invalid", "test-hash"));
        String key = UUID.randomUUID().toString();
        DatasetCreateCommand command = command("sales-" + UUID.randomUUID());

        var created = service.create(member.getId().toString(), key, command);
        var replayed = service.create(member.getId().toString(), key, command);

        assertThat(replayed).isEqualTo(created);
        assertThat(datasets.findById(Long.valueOf(created.datasetId()))).isPresent();
        assertThat(charts.findById(Long.valueOf(created.chartId()))).get()
                .extracting(chart -> chart.getDataset().getId()).isEqualTo(Long.valueOf(created.datasetId()));
    }

    @Test
    void rejectsDifferentPayloadForSameIdempotencyKey() throws Exception {
        Member member = members.saveAndFlush(Member.createEmailMember(
                "it-dataset-" + UUID.randomUUID() + "@example.invalid", "test-hash"));
        String key = UUID.randomUUID().toString();
        service.create(member.getId().toString(), key, command("first-" + UUID.randomUUID()));

        assertThatThrownBy(() -> service.create(member.getId().toString(), key,
                command("different-" + UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    private DatasetCreateCommand command(String name) throws Exception {
        return new DatasetCreateCommand(name, 1,
                json.readTree("{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\",10]]}"),
                new FirstChartCreateCommand("Chart", "BAR", 1,
                        json.readTree("{\"x\":\"month\",\"y\":\"sales\"}")));
    }
}
