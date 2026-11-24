package com.macstudio.backend.storage.controller;

import com.macstudio.backend.storage.dto.S3ConnectivityUploadResponse;
import com.macstudio.backend.storage.service.S3ConnectivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** local 프로필의 Swagger에서만 사용하는 private S3 연결 점검 API다. */
@RestController
@Profile("local")
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true")
@RequestMapping("/api/v1/storage/connectivity-check")
@Tag(name = "Local S3 connectivity")
public class S3ConnectivityController {
    private final S3ConnectivityService service;

    public S3ConnectivityController(S3ConnectivityService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "로컬 S3 이미지 업로드 연결 점검",
            description = "PNG/JPEG 이미지를 dev/connectivity-check/ 서버 생성 키로 업로드하고 HEAD 조회로 확인합니다.")
    public S3ConnectivityUploadResponse upload(
            @Parameter(description = "최대 10MiB PNG 또는 JPEG 이미지", required = true)
            @RequestPart("image") MultipartFile image) {
        return service.uploadAndVerify(image);
    }
}
