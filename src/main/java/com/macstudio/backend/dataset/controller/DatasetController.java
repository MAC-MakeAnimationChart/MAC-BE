package com.macstudio.backend.dataset.controller;

import com.macstudio.backend.dataset.dto.DatasetCreateCommand;
import com.macstudio.backend.dataset.dto.DatasetCreateResponse;
import com.macstudio.backend.dataset.dto.DatasetPatchCommand;
import com.macstudio.backend.dataset.dto.DatasetPatchResponse;
import com.macstudio.backend.dataset.service.DatasetCreationService;
import com.macstudio.backend.dataset.service.DatasetUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 데이터셋 최초 생성과 이후 이름·표 데이터 저장 HTTP 계약을 제공한다. */
@RestController
@RequestMapping("/api/v1/datasets")
@Tag(name = "Datasets")
public class DatasetController {
    private final DatasetCreationService creationService;
    private final DatasetUpdateService updateService;

    public DatasetController(DatasetCreationService creationService, DatasetUpdateService updateService) {
        this.creationService = creationService;
        this.updateService = updateService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "데이터셋과 첫 차트 원자 생성",
            description = "command JSON part를 검증해 dataset과 첫 chart를 한 DB 트랜잭션으로 저장합니다.")
    public ResponseEntity<DatasetCreateResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestPart("command") DatasetCreateCommand command) {
        DatasetCreateResponse response = creationService.create(jwt.getSubject(), idempotencyKey, command);
        return ResponseEntity.created(URI.create("/api/v1/datasets/" + response.datasetId())).body(response);
    }

    @PatchMapping(value = "/{datasetId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "데이터셋 이름·표 데이터 부분 저장",
            description = "실제 변경 컬럼만 갱신하고 데이터 변경 시 모든 하위 썸네일을 무효화합니다.")
    public DatasetPatchResponse patch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String datasetId,
            @Valid @RequestBody DatasetPatchCommand command) {
        return updateService.patch(jwt.getSubject(), datasetId, command);
    }
}
