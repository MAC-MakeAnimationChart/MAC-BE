package com.macstudio.backend.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/** 이메일 회원은 password를 보내고 소셜 회원은 재인증 쿠키를 사용한다. */
public record WithdrawalRequest(
        @Size(max = 72)
        String password,

        @AssertTrue
        boolean acknowledgeEmailReuseBlocked
) {
    /** 비밀번호 원문이 로그에 노출되지 않도록 한다. */
    @Override
    public String toString() {
        return "WithdrawalRequest[REDACTED]";
    }
}
