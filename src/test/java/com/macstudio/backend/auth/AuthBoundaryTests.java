package com.macstudio.backend.auth;

import com.macstudio.backend.auth.config.AuthRequestFilter;
import com.macstudio.backend.auth.service.AuthCrypto;
import com.macstudio.backend.auth.entity.AuthPurpose;
import com.macstudio.backend.global.exception.ApiException;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class AuthBoundaryTests {
    /** Origin 누락과 허용되지 않은 Origin을 예외 없이 403으로 거절한다. */
    @Test
    void rejectsMissingOriginAndHeader() throws Exception {
        var filter = new AuthRequestFilter(List.of("http://localhost:3000"), Clock.systemUTC());
        for (String origin : new String[]{null, "https://evil.example", "null"}) {
            var request = request();
            if (origin != null) request.addHeader("Origin", origin);
            request.addHeader("X-Requested-With", "MAC");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (a, b) -> { throw new AssertionError("거절 요청이 통과함"); });
            assertThat(response.getStatus()).isEqualTo(403);
        }
        var request = request();
        request.addHeader("Origin", "http://localhost:3000");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (a, b) -> { throw new AssertionError("헤더 없는 요청이 통과함"); });
        assertThat(response.getStatus()).isEqualTo(403);
    }

    /** 올바른 Origin·헤더라도 JSON 형식이 아니면 415로 거절한다. */
    @Test
    void requiresJsonAndLimitsRequests() throws Exception {
        var filter = new AuthRequestFilter(List.of("http://localhost:3000"), Clock.systemUTC());
        var request = request();
        request.addHeader("Origin", "http://localhost:3000");
        request.addHeader("X-Requested-With", "MAC");
        request.setContentType("text/plain");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (a,b) -> { throw new AssertionError("비 JSON 요청 통과"); });
        assertThat(response.getStatus()).isEqualTo(415);
        request.setContentType("application/json");
        request.setServletPath("/api/v1/auth/email/send-code");
        request.setRequestURI("/api/v1/auth/email/send-code");
        for (int i = 0; i < 6; i++) {
            response = new MockHttpServletResponse();
            var reached = new AtomicBoolean();
            filter.doFilter(request, response, (a,b) -> reached.set(true));
            assertThat(reached.get()).isEqualTo(i < 5);
            if (i == 5) assertThat(response.getStatus()).isEqualTo(429);
        }
    }

    /** 이메일별 HMAC 분리·난수 형식 및 미설정 거절을 검증한다. */
    @Test
    void protectsCodeAndProofMaterial() {
        var crypto = new AuthCrypto("MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
        assertThat(crypto.code(6)).matches("[0-9]{6}");
        assertThat(crypto.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(java.util.Arrays.equals(crypto.codeDigest("a@example.com", "123456"),
                crypto.codeDigest("b@example.com", "123456"))).isFalse();
        assertThat(java.util.Arrays.equals(crypto.codeDigest(AuthPurpose.SIGNUP, "a@example.com", "123456"),
                crypto.codeDigest(AuthPurpose.PASSWORD_RESET, "a@example.com", "123456"))).isFalse();
        assertThat(crypto.decoyChallengeId()).matches("[0-9]+");
        assertThatThrownBy(() -> new AuthCrypto("").codeDigest("a@example.com", "123456"))
                .isInstanceOf(ApiException.class);
    }

    /** 필터 경계를 검증할 기본 POST 요청을 만든다. */
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setContentType("application/json");
        return request;
    }
}
