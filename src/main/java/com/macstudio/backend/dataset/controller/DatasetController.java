package com.macstudio.backend.dataset.controller;

import com.macstudio.backend.chart.dto.ChartCreateCommand;
import com.macstudio.backend.chart.dto.ChartCreateResponse;
import com.macstudio.backend.chart.service.ChartCreationService;
import com.macstudio.backend.dataset.dto.DatasetCreateCommand;
import com.macstudio.backend.dataset.dto.DatasetCreateResponse;
import com.macstudio.backend.dataset.dto.DatasetChartPageResponse;
import com.macstudio.backend.dataset.dto.DatasetPatchCommand;
import com.macstudio.backend.dataset.dto.DatasetPatchResponse;
import com.macstudio.backend.dataset.service.DatasetCreationService;
import com.macstudio.backend.dataset.service.DatasetChartQueryService;
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

/** 데이터셋 생성·수정과 선택 데이터셋의 차트 조회·추가 HTTP 계약을 제공한다. */
@RestController
@RequestMapping("/api/v1/datasets")
@Tag(name = "Datasets")
public class DatasetController {
    private final DatasetCreationService creationService;
    private final DatasetUpdateService updateService;
    private final ChartCreationService chartCreationService;
    private final DatasetChartQueryService chartQueryService;

    public DatasetController(DatasetCreationService creationService, DatasetUpdateService updateService,
            ChartCreationService chartCreationService, DatasetChartQueryService chartQueryService) {
        this.creationService = creationService;
        this.updateService = updateService;
        this.chartCreationService = chartCreationService;
        this.chartQueryService = chartQueryService;
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

    @PostMapping(value = "/{datasetId}/charts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "기존 데이터셋에 차트 추가",
            description = "현재 dataset revision을 확인하고 동일 데이터를 공유하는 revision 1 차트를 생성합니다.")
    public ResponseEntity<ChartCreateResponse> createChart(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String datasetId,
            @Valid @RequestBody ChartCreateCommand command) {
        ChartCreateResponse response = chartCreationService.create(jwt.getSubject(), datasetId, command);
        return ResponseEntity.created(URI.create("/api/v1/charts/" + response.chartId())).body(response);
    }

    @GetMapping("/{datasetId}/charts")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "선택 데이터셋의 차트 페이지 조회",
            description = "데이터셋 기본 정보와 최신순 차트 카드를 독립 페이지로 반환합니다.")
    public DatasetChartPageResponse getCharts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String datasetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return chartQueryService.get(jwt.getSubject(), datasetId, page, size);
    }
}
