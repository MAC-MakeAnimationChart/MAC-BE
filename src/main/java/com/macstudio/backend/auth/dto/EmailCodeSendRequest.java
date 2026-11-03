package com.macstudio.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/** 가입 이메일 인증코드 발송 요청. */
public record EmailCodeSendRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Pattern(regexp = "signup|password_reset")
        String purpose
) {
    /** 이메일의 앞뒤 공백과 대소문자를 정리한 뒤 검증받도록 한다. */
    public EmailCodeSendRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }

    /** 요청 객체 출력으로 이메일이 로그에 남지 않도록 한다. */
    @Override
    public String toString() {
        return "EmailCodeSendRequest[REDACTED]";
    }
}
