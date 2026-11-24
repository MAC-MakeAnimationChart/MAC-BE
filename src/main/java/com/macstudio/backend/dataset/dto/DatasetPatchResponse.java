package com.macstudio.backend.dataset.dto;

import java.time.Instant;

/** 저장 후 다음 자동 저장 revision과 무효화한 썸네일 수를 반환한다. */
public record DatasetPatchResponse(
        String datasetId,
        String datasetRevision,
        int invalidatedThumbnailCount,
        Instant updatedAt
) {}
