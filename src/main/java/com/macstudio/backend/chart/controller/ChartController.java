package com.macstudio.backend.chart.controller;

import com.macstudio.backend.chart.dto.ChartPatchCommand;
import com.macstudio.backend.chart.dto.ChartPatchResponse;
import com.macstudio.backend.chart.service.ChartUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 차트 편집기의 명시적 저장과 자동 저장 HTTP 계약을 제공한다. */
@RestController
@RequestMapping("/api/v1/charts")
@Tag(name = "Charts")
public class ChartController {
    private final ChartUpdateService service;

    public ChartController(ChartUpdateService service) {
        this.service = service;
    }

    @PatchMapping(value = "/{chartId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "차트 옵션과 선택적 썸네일 저장",
            description = "변경된 옵션만 갱신하고 새 썸네일이 유효할 때 현재 S3 참조를 교체합니다.")
    public ChartPatchResponse patch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String chartId,
            @Valid @RequestPart("command") ChartPatchCommand command,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail) {
        return service.patch(jwt.getSubject(), chartId, command, thumbnail);
    }
}
