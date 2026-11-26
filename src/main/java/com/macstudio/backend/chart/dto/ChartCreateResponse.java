package com.macstudio.backend.chart.dto;

import java.time.Instant;

/** 추가된 차트와 소속 데이터셋의 현재 revision을 문자열로 반환한다. */
public record ChartCreateResponse(
        String datasetId,
        String datasetRevision,
        String chartId,
        String chartRevision,
        Instant createdAt
) {}
