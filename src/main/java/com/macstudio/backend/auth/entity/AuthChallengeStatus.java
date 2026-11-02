package com.macstudio.backend.auth.entity;

/** 만료는 별도 상태가 아니라 만료 시각 비교로 판단한다. */
public enum AuthChallengeStatus {
    PENDING, VERIFIED, CONSUMED, CANCELLED
}
