package com.macstudio.backend.auth.dto;

import com.macstudio.backend.member.dto.MemberResponse;

/** 회원가입·로그인 성공 시 반환하는 Access 토큰과 회원 정보. */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        MemberResponse member
) {
    /** Access 토큰이 로그에 노출되지 않도록 한다. */
    @Override
    public String toString() {
        return "AuthResponse[REDACTED]";
    }
}