package com.macstudio.backend.dataset.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 정규화된 편집 데이터와 첫 차트를 한 번에 생성하는 명령이다. */
public record DatasetCreateCommand(
        @NotBlank @Size(max = 120) String name,
        @Min(1) int dataSchemaVersion,
        @NotNull JsonNode data,
        @NotNull @Valid FirstChartCreateCommand firstChart
) {}
