package com.macstudio.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/** 이메일 로그인 자격 증명. */
public record LoginRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(max = 72)
        String password
) {
    /** 조회할 이메일만 정규화한다. 비밀번호 원문은 그대로 유지한다. */
    public LoginRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }

    /** 로그인 자격 증명이 로그에 노출되지 않도록 한다. */
    @Override
    public String toString() {
        return "LoginRequest[REDACTED]";
    }
}
