package com.macstudio.backend.auth.service;

import com.macstudio.backend.global.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 코드 HMAC과 무작위 증명 처리를 담당한다. 원문·키를 로그에 남기지 않는다. */
@Component
public class AuthCrypto {
    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    /** 서버 비밀키를 검증한다. 미설정 시 서버는 시작하되 이메일 인증 요청은 거절한다. */
    public AuthCrypto(@Value("${app.auth.hmac-secret-base64:}") String secret) {
        try { key = secret.isBlank() ? null : Base64.getDecoder().decode(secret); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("Invalid AUTH_HMAC_SECRET_BASE64 encoding"); }
        if (key != null && key.length < 32) throw new IllegalStateException("Auth HMAC key requires at least 32 bytes");
    }
    /** 각 자리를 독립 난수로 뽑아 앞자리 0도 유지하는 인증코드를 만든다. */
    public String code(int length) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < length; i++) code.append(random.nextInt(10));
        return code.toString();
    }
    /** 가입 증명·Refresh에 사용할 256비트 난수의 URL-safe 표현을 만든다. */
    public String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32)); }
    /** 요청한 길이의 보안 난수를 만든다. */
    public byte[] randomBytes(int count) { byte[] value = new byte[count]; random.nextBytes(value); return value; }
    /** 목적·이메일·코드를 함께 HMAC하여 다른 이메일의 코드 해시 재사용을 막는다. */
    public byte[] codeDigest(String email, String code) {
        if (key == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_CONFIGURED", "인증 설정이 필요합니다.");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(("SIGNUP\u0000" + email + "\u0000" + code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("HMAC unavailable"); }
    }
    /** 충분한 난수를 가진 증명·Refresh 원문을 SHA-256으로 변환한다. */
    public byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
