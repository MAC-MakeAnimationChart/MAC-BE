package com.macstudio.backend.auth.service;

import com.macstudio.backend.auth.config.SocialAuthProperties;
import com.macstudio.backend.auth.dto.SocialAuthRequest;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.IdentityProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Google·Kakao·Naver 공식 endpoint에서 code를 교환하고 사용자 식별자를 조회한다. */
@Component
public class HttpSocialProviderClient implements SocialProviderClient {
    private static final int MAX_RESPONSE_CHARS = 1_000_000;
    private final SocialAuthProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpSocialProviderClient(SocialAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public VerifiedSocialIdentity exchange(SocialAuthRequest request) {
        IdentityProvider provider = provider(request.provider());
        SocialAuthProperties.Provider config = config(provider);
        requireConfigured(config);
        if (!config.redirectUris().contains(request.redirectUri()))
            throw bad("INVALID_REDIRECT_URI", "허용되지 않은 소셜 로그인 callback 주소입니다.");
        validateProviderInput(provider, request);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", config.clientId());
        form.put("client_secret", config.clientSecret());
        form.put("redirect_uri", request.redirectUri());
        form.put("code", request.code());
        if (provider != IdentityProvider.NAVER) form.put("code_verifier", request.codeVerifier());
        if (provider == IdentityProvider.NAVER) form.put("state", request.state());

        JsonNode token = postForm(config.tokenUri(), form, true);
        String accessToken = text(token, "access_token");
        if (accessToken == null) throw providerError();
        JsonNode profile = getJson(config.userInfoUri(), accessToken);
        return switch (provider) {
            case GOOGLE -> google(profile);
            case KAKAO -> kakao(profile);
            case NAVER -> naver(profile);
        };
    }

    private VerifiedSocialIdentity google(JsonNode profile) {
        String subject = requiredText(profile, "sub");
        String email = profile.path("email_verified").asBoolean(false) ? normalizedEmail(text(profile, "email")) : null;
        return new VerifiedSocialIdentity(IdentityProvider.GOOGLE, subject, email);
    }

    private VerifiedSocialIdentity kakao(JsonNode profile) {
        String subject = profile.path("id").isMissingNode() || profile.path("id").isNull()
                ? null : profile.path("id").asText();
        if (subject == null || subject.isBlank()) throw providerError();
        JsonNode account = profile.path("kakao_account");
        boolean trusted = account.path("is_email_valid").asBoolean(false)
                && account.path("is_email_verified").asBoolean(false);
        String email = trusted ? normalizedEmail(text(account, "email")) : null;
        return new VerifiedSocialIdentity(IdentityProvider.KAKAO, subject, email);
    }

    private VerifiedSocialIdentity naver(JsonNode profile) {
        if (!"00".equals(text(profile, "resultcode"))) throw providerError();
        JsonNode response = profile.path("response");
        return new VerifiedSocialIdentity(IdentityProvider.NAVER, requiredText(response, "id"),
                normalizedEmail(text(response, "email")));
    }

    private JsonNode postForm(String uri, Map<String, String> values, boolean codeExchange) {
        String body = values.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        HttpRequest request = HttpRequest.newBuilder(safeUri(uri)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request, codeExchange);
    }

    private JsonNode getJson(String uri, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(safeUri(uri)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json").GET().build();
        return send(request, false);
    }

    private JsonNode send(HttpRequest request, boolean codeExchange) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (codeExchange && response.statusCode() == 400)
                    throw bad("INVALID_CODE", "소셜 로그인 인증 코드가 만료되었거나 이미 사용되었습니다.");
                throw providerError();
            }
            if (response.body() == null || response.body().length() > MAX_RESPONSE_CHARS) throw providerError();
            return json.readTree(response.body());
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw providerError();
        } catch (Exception e) {
            throw providerError();
        }
    }

    private void validateProviderInput(IdentityProvider provider, SocialAuthRequest request) {
        if (provider == IdentityProvider.NAVER) {
            if (request.state() == null || request.state().isBlank())
                throw bad("INVALID_REQUEST", "네이버 로그인 state 값이 필요합니다.");
            return;
        }
        if (request.codeVerifier() == null
                || !request.codeVerifier().matches("[A-Za-z0-9._~-]{43,128}"))
            throw bad("INVALID_REQUEST", "소셜 로그인 PKCE 검증값을 확인해 주세요.");
    }

    private void requireConfigured(SocialAuthProperties.Provider config) {
        if (config.clientId() == null || config.clientId().isBlank()
                || config.clientSecret() == null || config.clientSecret().isBlank())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_AUTH_NOT_CONFIGURED", "소셜 로그인 설정이 필요합니다.");
    }

    private IdentityProvider provider(String value) {
        try { return IdentityProvider.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException e) { throw bad("UNSUPPORTED_PROVIDER", "지원하지 않는 소셜 로그인 제공사입니다."); }
    }
    private SocialAuthProperties.Provider config(IdentityProvider provider) {
        return switch (provider) {
            case GOOGLE -> properties.google();
            case KAKAO -> properties.kakao();
            case NAVER -> properties.naver();
        };
    }
    private static URI safeUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException e) { throw new IllegalStateException("Invalid social provider endpoint"); }
    }
    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 255) throw providerError();
        return value;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
    private static String normalizedEmail(String value) {
        if (value == null || value.isBlank()) return null;
        String email = value.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || email.chars().anyMatch(Character::isWhitespace)
                || email.indexOf('@') <= 0 || email.endsWith("@")) return null;
        return email;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException providerError() { return new ApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR", "소셜 로그인 제공사와 통신하지 못했습니다."); }
}
