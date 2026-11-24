package com.macstudio.backend.chart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 명시적 저장과 자동 저장이 공통으로 사용하는 차트 부분 수정 명령이다. */
public record ChartPatchCommand(
        @NotBlank String expectedRevision,
        @Size(max = 255) String title,
        @Size(max = 30) String chartType,
        Integer figureSchemaVersion,
        JsonNode figure
) {}
