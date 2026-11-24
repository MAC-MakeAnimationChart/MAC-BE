package com.macstudio.backend.dataset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 데이터셋 이름과 현재 표 데이터를 부분 수정하는 자동 저장 명령이다. */
public record DatasetPatchCommand(
        @NotBlank String expectedRevision,
        @Size(max = 120) String name,
        Integer dataSchemaVersion,
        JsonNode data
) {}
