package com.macstudio.backend.auth.dto;

/** 이메일 인증 완료 후 발급하는 가입용 증명. 로그인 토큰은 아니다. */
public record EmailVerifyResponse(
        String verificationToken,
        long expiresIn
) {
    /** 가입 증명 원문이 로그에 노출되지 않도록 한다. */
    @Override
    public String toString() {
        return "EmailVerifyResponse[REDACTED]";
    }
}