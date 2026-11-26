package com.macstudio.backend.dataset.dto;

import java.time.Instant;
import java.util.List;

/** 선택 데이터셋 기본 정보와 차트 카드 한 페이지를 반환한다. */
public record DatasetChartPageResponse(
        DatasetInfo dataset,
        List<ChartItem> charts,
        PageInfo page
) {
    /** 차트 영역 상단에 필요한 선택 데이터셋의 현재 정보를 제공한다. */
    public record DatasetInfo(String datasetId, String name, String revision,
            int rowCount, int columnCount, long chartCount, Instant updatedAt) {}

    /** 차트 카드 렌더링·편집에 필요한 식별자와 썸네일 상태를 제공한다. */
    public record ChartItem(String chartId, String title, String chartType, String revision,
            String thumbnailState, String thumbnailUrl, Instant updatedAt) {}

    /** 0부터 시작하는 차트 페이지 위치와 다음 페이지 존재 여부를 제공한다. */
    public record PageInfo(int number, int size, long totalElements, int totalPages,
            boolean hasNext) {}
}
