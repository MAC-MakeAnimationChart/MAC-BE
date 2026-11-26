package com.macstudio.backend.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberRepository;
import com.macstudio.backend.member.service.MyPageQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** 마이페이지 요약과 차트 본문 없는 데이터셋 폴더 페이지 조립을 검증한다. */
class MyPageQueryServiceTests {
    private MemberRepository members;
    private DatasetRepository datasets;
    private ChartRepository charts;
    private MyPageQueryService service;
    private Member member;
    private Dataset dataset;

    @BeforeEach
    void setUp() {
        members = mock(MemberRepository.class);
        datasets = mock(DatasetRepository.class);
        charts = mock(ChartRepository.class);
        member = Member.createEmailMember("mypage@example.com", "encoded-password");
        ReflectionTestUtils.setField(member, "id", 7L);
        dataset = Dataset.create(member, UUID.randomUUID(), "marketing.csv", 1,
                "{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\",10]]}",
                60L, 1, 2);
        ReflectionTestUtils.setField(dataset, "id", 11L);
        ReflectionTestUtils.setField(dataset, "createdAt", LocalDateTime.of(2026, 11, 24, 10, 0));
        ReflectionTestUtils.setField(dataset, "updatedAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        when(members.findById(7L)).thenReturn(Optional.of(member));
        when(datasets.findMyPageByMemberId(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("market"), any())).thenReturn(
                new PageImpl<>(List.of(dataset), PageRequest.of(0, 20), 2));
        when(datasets.countByMember_Id(7L)).thenReturn(2L);
        when(charts.countByDataset_Member_Id(7L)).thenReturn(4L);
        when(datasets.calculateStorageBytes(7L)).thenReturn(12_345L);
        service = new MyPageQueryService(members, datasets, charts, 30L * 1024 * 1024);
    }

    @Test
    void returnsSummaryAndDatasetFoldersWithoutNestedCharts() {
        ChartRepository.DatasetChartCount count = mock(ChartRepository.DatasetChartCount.class);
        when(count.getDatasetId()).thenReturn(11L);
        when(count.getChartCount()).thenReturn(2L);
        when(charts.countByDatasetIds(List.of(11L))).thenReturn(List.of(count));

        var response = service.get("7", 0, 20, " market ");

        assertThat(response.email()).isEqualTo("mypage@example.com");
        assertThat(response.summary().datasetCount()).isEqualTo(2L);
        assertThat(response.summary().chartCount()).isEqualTo(4L);
        assertThat(response.summary().storageUsedBytes()).isEqualTo(12_345L);
        assertThat(response.datasets()).hasSize(1);
        assertThat(response.datasets().get(0).rowCount()).isEqualTo(1);
        assertThat(response.datasets().get(0).chartCount()).isEqualTo(2);
        assertThat(response.page().totalElements()).isEqualTo(1L);
    }

    @Test
    void rejectsUnsafePageBeforeQueryingDatabase() {
        assertThatThrownBy(() -> service.get("7", 0, 51, ""))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_PAGE"));
        verify(members, never()).findById(any());
    }
}
