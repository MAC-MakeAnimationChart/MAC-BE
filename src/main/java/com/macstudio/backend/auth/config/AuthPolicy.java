package com.macstudio.backend.auth.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 운영 확정 전 로컬 기본값을 외부에서 변경할 수 있는 가입·토큰 정책. */
@Validated
@ConfigurationProperties("app.auth.policy")
public record AuthPolicy(
        @Min(1) @Max(600) long accessTtlSeconds,
        @Min(1) long refreshTtlSeconds,
        @Min(1) long familyTtlSeconds,
        @Min(1) @Max(72) int passwordMinLength,
        @NotBlank @Size(max = 40) String termsVersion,
        @NotBlank @Size(max = 40) String privacyVersion,
        @Min(1) int sendsPerHour,
        boolean secureCookie
) {}
