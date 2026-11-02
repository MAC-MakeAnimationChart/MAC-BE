package com.macstudio.backend.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 소셜 제공사별 서버 자격 증명·엔드포인트·허용 callback을 보관한다. */
@Validated
@ConfigurationProperties("app.auth.social")
public record SocialAuthProperties(
        @Min(60) long signupTtlSeconds,
        @Min(60) long reauthTtlSeconds,
        @NotNull @Valid Provider google,
        @NotNull @Valid Provider kakao,
        @NotNull @Valid Provider naver
) {
    /** 제공사 설정. client secret은 환경 변수로만 주입하고 응답·로그에 노출하지 않는다. */
    public record Provider(
            String clientId,
            String clientSecret,
            @NotBlank String tokenUri,
            @NotBlank String userInfoUri,
            @NotNull List<@NotBlank String> redirectUris
    ) {
        public Provider {
            redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        }
    }
}
