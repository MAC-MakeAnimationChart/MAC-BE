package com.macstudio.backend.chart.dto;

import java.time.Instant;

/** 저장 후 FE가 다음 자동 저장에 사용할 현재 revision과 썸네일 상태를 반환한다. */
public record ChartPatchResponse(
        String chartId,
        String datasetId,
        String chartRevision,
        String datasetRevision,
        String thumbnailState,
        Instant updatedAt
) {}
