package com.macstudio.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** 이메일 인증 증명으로 새 비밀번호를 설정한다. 비밀번호 확인 일치는 프론트 UX 책임이다. */
public record PasswordResetRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 512) String verificationToken,
        @NotBlank @Size(max = 72) String newPassword
) {
    /** 회원 조회와 증명 대상 비교에 사용할 이메일을 가입·로그인과 같은 방식으로 정규화한다. */
    public PasswordResetRequest {
        if (email != null) email = email.strip().toLowerCase(Locale.ROOT);
    }

    /** 비밀번호와 인증 증명이 로그에 노출되지 않도록 한다. */
    @Override public String toString() { return "PasswordResetRequest[REDACTED]"; }
}
