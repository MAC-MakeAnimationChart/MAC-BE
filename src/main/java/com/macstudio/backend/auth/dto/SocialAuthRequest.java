package com.macstudio.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 프론트 callback이 받은 인가 코드를 백엔드 code 교환에 전달한다. */
public record SocialAuthRequest(
        @NotBlank @Pattern(regexp = "(?i)GOOGLE|KAKAO|NAVER") String provider,
        @NotBlank @Size(max = 2048) String code,
        @Size(max = 128) String codeVerifier,
        @NotBlank @Size(max = 2048) String redirectUri,
        @Size(max = 512) String state
) {
    @Override public String toString() { return "SocialAuthRequest[REDACTED]"; }
}
