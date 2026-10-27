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
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendSmokeTests {
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
}
