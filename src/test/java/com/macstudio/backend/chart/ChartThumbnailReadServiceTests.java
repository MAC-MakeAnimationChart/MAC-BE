package com.macstudio.backend.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.chart.service.ChartThumbnailReadService;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.storage.service.ChartThumbnailStorage;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 인증된 현재 차트 썸네일 조회의 소유권과 미생성 상태를 검증한다. */
class ChartThumbnailReadServiceTests {
    private ChartRepository charts;
    private ChartThumbnailStorage thumbnails;
    private ChartThumbnailReadService service;
    private Chart chart;

    @BeforeEach
    void setUp() {
        charts = mock(ChartRepository.class);
        thumbnails = mock(ChartThumbnailStorage.class);
        Member member = Member.createEmailMember("owner@example.com", "encoded-password");
        ReflectionTestUtils.setField(member, "id", 7L);
        Dataset dataset = Dataset.create(member, UUID.randomUUID(), "sales", 1,
                "{\"headers\":[\"x\"],\"rows\":[]}", 30L, 0, 1);
        ReflectionTestUtils.setField(dataset, "id", 11L);
        chart = Chart.create(dataset, "Sales", "BAR", 1, "{\"x\":\"x\"}");
        ReflectionTestUtils.setField(chart, "id", 21L);
        ReflectionTestUtils.setField(chart, "createdAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        ReflectionTestUtils.setField(chart, "updatedAt", LocalDateTime.of(2026, 11, 26, 10, 0));
        when(charts.findOwnedById(21L, 7L)).thenReturn(Optional.of(chart));
        service = new ChartThumbnailReadService(charts, thumbnails);
    }

    @Test
    void returnsOwnedPrivateThumbnail() {
        chart.attachThumbnail("dev/chart-thumbnails/21/current.png", 3L, "image/png", 1L);
        when(thumbnails.download("dev/chart-thumbnails/21/current.png"))
                .thenReturn(new byte[]{1, 2, 3});

        var result = service.read("7", "21");

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).containsExactly(1, 2, 3);
    }

    @Test
    void returnsNotFoundWhenThumbnailDoesNotExist() {
        assertThatThrownBy(() -> service.read("7", "21"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("THUMBNAIL_NOT_FOUND"));
    }
}
