package com.macstudio.backend.auth.entity;

/** 제공사 code 교환 목적을 로그인/가입 탐색과 민감 작업 재인증으로 구분한다. */
public enum OAuthIntent {
    LOGIN, REAUTH
}
