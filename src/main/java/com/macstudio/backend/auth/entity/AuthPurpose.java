package com.macstudio.backend.auth.entity;

/** 인증 증명이 허용하는 작업을 구분한다. 현재 생성 경로는 SIGNUP만 제공한다. */
public enum AuthPurpose {
    SIGNUP, PASSWORD_RESET, EMAIL_CHANGE, PASSWORD_SET,
    PASSWORD_CHANGE, IDENTITY_LINK, IDENTITY_UNLINK, ACCOUNT_DELETE
}
