package com.macstudio.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import org.mockito.Mockito;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendSmokeTests {
    // 이 테스트는 HTTP/Security 경계만 검사한다. DB 업무 로직은 별도 통합 테스트에서 검증한다.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.macstudio.backend.auth.service.AccountAuthService accountAuthService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.macstudio.backend.auth.service.SocialAuthService socialAuthService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.macstudio.backend.auth.service.SocialSignupService socialSignupService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.macstudio.backend.auth.service.EmailVerificationService emailVerificationService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.macstudio.backend.auth.service.TokenService tokenService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.macstudio.backend.auth.service.EmailRequestLock emailRequestLock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.macstudio.backend.member.service.MemberService memberService;
    @LocalServerPort
    private int port;

    @Autowired
    private SecretKey jwtSecretKey;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void publicHealthAndOpenApiAreAvailable() throws Exception {
        var health = get("/api/v1/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("mac-studio-backend", "UP");
        assertThat(get("/v3/api-docs", null).statusCode()).isEqualTo(200);
        assertThat(get("/swagger-ui/index.html", null).statusCode()).isEqualTo(200);
    }

    @Test
    void privatePathsRejectMissingOrMalformedBearerTokens() throws Exception {
        assertThat(get("/api/v1/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/me", "invalid").statusCode()).isEqualTo(401);
        assertThat(withdraw(null, true).statusCode()).isEqualTo(401);
    }

    @Test
    void withdrawalRequiresOriginAndAcknowledgementContract() throws Exception {
        String token = token("mac-studio", "mac-studio-api", 300);
        assertThat(withdraw(token, false).statusCode()).isEqualTo(403);
        var response = withdraw(token, true);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("Set-Cookie").orElse(""))
                .contains("mac_refresh=", "Max-Age=0", "HttpOnly", "SameSite=Lax");
    }

    @Test
    void signedJwtMustHaveCorrectIssuerAudienceAndExpiry() throws Exception {
        // Public endpoint still runs JWT validation when a Bearer token is supplied.
        assertThat(get("/api/v1/health", token("mac-studio", "mac-studio-api", 300)).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/health", token("wrong", "mac-studio-api", 300)).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/health", token("mac-studio", "wrong", 300)).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/health", token("mac-studio", "mac-studio-api", -300)).statusCode()).isEqualTo(401);
    }

    @Test
    void corsAllowsOnlyConfiguredOrigin() throws Exception {
        assertThat(preflight("http://localhost:3000").headers()
                .firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(preflight("https://untrusted.example").statusCode()).isEqualTo(403);
    }

    @Test
    void socialExchangeIsPublicButSocialReauthenticationRequiresJwt() throws Exception {
        Mockito.when(socialAuthService.exchange(Mockito.any())).thenReturn(
                new com.macstudio.backend.auth.service.SocialAuthService.ExchangeResult(null,
                        new com.macstudio.backend.auth.dto.SocialSignupPendingResponse(true, "GOOGLE", "user@example.com"),
                        "social-signup-token-with-exact-shape-123456", 600));
        String body = "{\"provider\":\"google\",\"code\":\"code\",\"codeVerifier\":\""
                + "a".repeat(43) + "\",\"redirectUri\":\"http://localhost:3000/auth/callback/google\"}";

        assertThat(authPost("/api/v1/auth/social", body, null).statusCode()).isEqualTo(202);
        assertThat(authPost("/api/v1/auth/social/reauth", body, null).statusCode()).isEqualTo(401);
    }

    private String token(String issuer, String audience, long expiresInSeconds) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .subject("1").issuer(issuer).audience(audience)
                .expirationTime(Date.from(Instant.now().plusSeconds(expiresInSeconds))).build());
        jwt.sign(new MACSigner(jwtSecretKey.getEncoded()));
        return jwt.serialize();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me"))
                .header("Origin", origin).header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> withdraw(String token, boolean validOrigin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/withdraw"))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (validOrigin) request.header("Origin", "http://localhost:3000").header("X-Requested-With", "MAC");
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(
                "{\"password\":\"Current-password-493!\",\"acknowledgeEmailReuseBlocked\":true}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }


    private HttpResponse<String> authPost(String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000").header("X-Requested-With", "MAC");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
