package com.macstudio.backend.chart.dto;

import java.time.Instant;

/** 현재 차트에 결합된 썸네일과 렌더링 기준 revision을 반환한다. */
public record ChartThumbnailSaveResponse(
        String chartId,
        String datasetId,
        String chartRevision,
        String datasetRevision,
        String thumbnailState,
        Instant updatedAt
) {}
