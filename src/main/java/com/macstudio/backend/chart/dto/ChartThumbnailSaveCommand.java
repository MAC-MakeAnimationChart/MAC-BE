package com.macstudio.backend.chart.dto;

import jakarta.validation.constraints.NotBlank;

/** 프론트 렌더링에 사용한 dataset/chart revision을 전달하는 썸네일 저장 명령이다. */
public record ChartThumbnailSaveCommand(
        @NotBlank String basedOnDatasetRevision,
        @NotBlank String basedOnChartRevision
) {}
