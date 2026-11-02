package com.macstudio.backend.auth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 이메일 인증 정책을 외부 설정에서 읽는다. 시간 단위는 초다. */
@Validated
@ConfigurationProperties(prefix = "app.auth.email")
public record EmailVerificationProperties(
        @Min(4)
        @Max(10)
        int codeLength,

        @Min(1)
        long codeTtlSeconds,

        @Min(1)
        long proofTtlSeconds,

        @Min(1)
        @Max(65535)
        int maxAttempts,

        @Min(1)
        long resendAfterSeconds
) {
}