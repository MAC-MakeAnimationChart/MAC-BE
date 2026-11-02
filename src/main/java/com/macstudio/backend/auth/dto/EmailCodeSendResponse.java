package com.macstudio.backend.auth.dto;

/**
 * 인증코드 발송 요청 수락 결과.
 * expiresIn과 resendAfter의 단위는 초다.
 */
public record EmailCodeSendResponse(
        String challengeId,
        long expiresIn,
        long resendAfter
) {
}