package com.macstudio.backend.chart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 기존 데이터셋의 현재 revision을 기준으로 새 차트를 생성하는 명령이다. */
public record ChartCreateCommand(
        @NotBlank String expectedDatasetRevision,
        @Size(max = 255) String title,
        @NotBlank @Size(max = 30) String chartType,
        @Min(1) int figureSchemaVersion,
        @NotNull JsonNode figure
) {}
