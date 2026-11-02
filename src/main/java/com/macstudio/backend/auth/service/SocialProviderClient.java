package com.macstudio.backend.auth.service;

import com.macstudio.backend.auth.dto.SocialAuthRequest;

/** 외부 OAuth 제공사 code 교환 경계. 업무 서비스와 네트워크 구현을 분리한다. */
public interface SocialProviderClient {
    VerifiedSocialIdentity exchange(SocialAuthRequest request);
}
