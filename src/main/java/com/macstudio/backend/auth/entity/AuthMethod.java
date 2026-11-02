package com.macstudio.backend.auth.entity;

/** 인증 수단을 구분한다. 이번 구현은 EMAIL_CODE 상태 전이만 제공한다. */
public enum AuthMethod {
    EMAIL_CODE, PASSWORD, SOCIAL
}
