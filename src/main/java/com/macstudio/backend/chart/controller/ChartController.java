package com.macstudio.backend.chart.controller;

import com.macstudio.backend.chart.dto.ChartPatchCommand;
import com.macstudio.backend.chart.dto.ChartPatchResponse;
import com.macstudio.backend.chart.dto.ChartThumbnailSaveCommand;
import com.macstudio.backend.chart.dto.ChartThumbnailSaveResponse;
import com.macstudio.backend.chart.service.ChartThumbnailService;
import com.macstudio.backend.chart.service.ChartThumbnailReadService;
import com.macstudio.backend.chart.service.ChartUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** 차트 옵션 저장과 렌더링 완료 후 썸네일 저장 HTTP 계약을 분리해 제공한다. */
@RestController
@RequestMapping("/api/v1/charts")
@Tag(name = "Charts")
public class ChartController {
    private final ChartUpdateService updateService;
    private final ChartThumbnailService thumbnailService;
    private final ChartThumbnailReadService thumbnailReadService;

    public ChartController(ChartUpdateService updateService, ChartThumbnailService thumbnailService,
            ChartThumbnailReadService thumbnailReadService) {
        this.updateService = updateService;
        this.thumbnailService = thumbnailService;
        this.thumbnailReadService = thumbnailReadService;
    }

    @PatchMapping(value = "/{chartId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "차트 옵션 저장",
            description = "변경된 옵션만 갱신하고 실제 설정 변경 시 기존 썸네일을 무효화합니다.")
    public ChartPatchResponse patch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String chartId,
            @Valid @RequestBody ChartPatchCommand command) {
        return updateService.patch(jwt.getSubject(), chartId, command);
    }

    @PutMapping(value = "/{chartId}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "렌더링 완료 썸네일 저장",
            description = "렌더링 기준 dataset/chart revision이 모두 현재 값일 때만 현재 S3 썸네일을 교체합니다.")
    public ChartThumbnailSaveResponse saveThumbnail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String chartId,
            @Valid @RequestPart("command") ChartThumbnailSaveCommand command,
            @RequestPart("thumbnail") MultipartFile thumbnail) {
        return thumbnailService.save(jwt.getSubject(), chartId, command, thumbnail);
    }

    @GetMapping("/{chartId}/thumbnail")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "현재 차트 썸네일 조회",
            description = "차트 소유권을 확인한 뒤 private S3의 현재 PNG/JPEG 바이트를 반환합니다.")
    public ResponseEntity<byte[]> getThumbnail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String chartId) {
        var content = thumbnailReadService.read(jwt.getSubject(), chartId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.bytes());
    }
}
