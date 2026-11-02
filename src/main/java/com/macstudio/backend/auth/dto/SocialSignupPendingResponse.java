package com.macstudio.backend.auth.dto;

/** 제공사 신원 확인은 끝났지만 약관 동의 전이라 회원을 만들지 않은 응답. */
public record SocialSignupPendingResponse(
        boolean signupRequired,
        String provider,
        String email
) {}
