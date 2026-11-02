package com.macstudio.backend.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 인증 설정 객체를 Spring Bean으로 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EmailVerificationProperties.class, AuthPolicy.class, SocialAuthProperties.class})
public class AuthConfig {
    /** 인증 만료 판단에 사용할 UTC 시계를 주입 가능하게 제공한다. */
    @org.springframework.context.annotation.Bean
    java.time.Clock authClock() { return java.time.Clock.systemUTC(); }
}
