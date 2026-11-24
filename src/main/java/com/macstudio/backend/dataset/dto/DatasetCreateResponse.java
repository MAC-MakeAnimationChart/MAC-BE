package com.macstudio.backend.dataset.dto;

import java.time.Instant;

/** 최초 데이터셋·차트 생성 결과의 문자열 ID와 독립 revision을 반환한다. */
public record DatasetCreateResponse(
        String datasetId,
        String datasetRevision,
        String chartId,
        String chartRevision,
        Instant createdAt
) {}
