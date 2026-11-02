package com.macstudio.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 이메일로 받은 코드와 인증 요청 식별자를 전달한다. */
public record EmailVerifyRequest(
        @NotBlank
        @Size(max = 19)
        @Pattern(regexp = "[1-9][0-9]*")
        String challengeId,

        @NotBlank
        @Size(max = 32)
        String code
) {
    /** 인증코드가 자동 생성 문자열을 통해 로그에 노출되지 않도록 한다. */
    @Override
    public String toString() {
        return "EmailVerifyRequest[REDACTED]";
    }
}