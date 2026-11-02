package com.macstudio.backend.global.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    /** 공개 인증 경로를 메서드별로 열고 인증 POST에는 Origin 기반 CSRF 방어를 적용한다. */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${springdoc.api-docs.enabled:false}") boolean docsEnabled,
            @Value("${app.cors.allowed-origins}") List<String> origins, java.time.Clock clock) throws Exception {
        // 기본 CSRF 토큰 대신 모든 쿠키 인증 POST에 정확한 Origin + 사용자 지정 헤더를 강제한다.
        org.springframework.security.web.AuthenticationEntryPoint unauthorized =
                (request, response, error) -> writeSecurityError(response, 401, "UNAUTHORIZED");
        org.springframework.security.web.access.AccessDeniedHandler forbidden =
                (request, response, error) -> writeSecurityError(response, 403, "FORBIDDEN");
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .addFilterBefore(new com.macstudio.backend.auth.config.AuthRequestFilter(origins, clock),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/email/send-code", "/api/v1/auth/email/verify",
                            "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/social",
                            "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll();
                    if (docsEnabled) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    /** 비밀번호 저장·검증에 동일한 BCrypt 인코더를 사용한다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 명시한 프론트 Origin에서만 자격 증명 쿠키와 사용자 지정 헤더를 허용한다. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-Requested-With"));
        config.setExposedHeaders(List.of("ETag", "Location"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Spring Security 필터 단계 오류도 비밀값 없는 공통 JSON으로 반환한다. */
    private static void writeSecurityError(jakarta.servlet.http.HttpServletResponse response, int status, String code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"인증 또는 접근 권한을 확인해 주세요.\"}");
    }
}
