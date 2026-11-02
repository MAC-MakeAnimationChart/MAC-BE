package com.macstudio.backend.auth.service;

import com.macstudio.backend.member.entity.IdentityProvider;

/** 제공사 토큰으로 직접 확인한 불변 식별자와 신뢰 가능한 이메일만 전달한다. */
public record VerifiedSocialIdentity(
        IdentityProvider provider,
        String subject,
        String email
) {
    @Override public String toString() { return "VerifiedSocialIdentity[REDACTED]"; }
}
