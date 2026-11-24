package com.macstudio.backend.dataset.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 최초 데이터셋과 함께 저장할 첫 차트의 FE 렌더링 설정이다. */
public record FirstChartCreateCommand(
        @Size(max = 255) String title,
        @NotBlank @Size(max = 30) String chartType,
        @Min(1) int figureSchemaVersion,
        @NotNull JsonNode figure
) {}
