package com.macstudio.backend.auth.service;

import com.macstudio.backend.auth.config.AuthPolicy;
import com.macstudio.backend.auth.dto.AuthResponse;
import com.macstudio.backend.auth.entity.RefreshToken;
import com.macstudio.backend.auth.repository.RefreshTokenRepository;
import com.macstudio.backend.member.dto.MemberResponse;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberIdentityRepository;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TokenService {
    /** HTTP 본문과 쿠키로 분리할 내부 결과. 컨트롤러는 이 객체 자체를 응답하지 않는다. */
    public record IssuedTokens(AuthResponse response, String refreshToken, long maxAge) {
        /** 내부 토큰 묶음이 로그에 출력되지 않도록 한다. */
        @Override public String toString() { return "IssuedTokens[REDACTED]"; }
    }
    private final RefreshTokenRepository tokens;
    private final MemberIdentityRepository identities;
    private final AuthCrypto crypto;
    private final AuthPolicy policy;
    private final SecretKey signingKey;
    private final String issuer;
    private final String audience;
    /** 기존 JWT 검증 키와 동일한 키·issuer·audience로 발급하도록 연결한다. */
    public TokenService(RefreshTokenRepository tokens, MemberIdentityRepository identities, AuthCrypto crypto,
            AuthPolicy policy, SecretKey signingKey, @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.audience}") String audience) {
        this.tokens = tokens; this.identities = identities; this.crypto = crypto; this.policy = policy;
        this.signingKey = signingKey; this.issuer = issuer; this.audience = audience;
    }
    /** 가입·로그인 트랜잭션 안에서 새 로그인 family를 생성한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedTokens start(Member member, Instant now) {
        return issue(member, crypto.randomBytes(16), now, now.plusSeconds(policy.familyTtlSeconds()));
    }
    /** 회전 시 기존 family와 절대 기한을 유지한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedTokens rotate(Member member, RefreshToken previous, Instant now) {
        return issue(member, previous.getFamilyId(), now, previous.getFamilyExpiresAt().toInstant(ZoneOffset.UTC));
    }
    /** Refresh 해시는 DB에 저장하고 원문은 쿠키 전용 결과로 분리한다. */
    private IssuedTokens issue(Member member, byte[] family, Instant now, Instant familyExpiry) {
        String refresh = crypto.token();
        Instant expiry = now.plusSeconds(policy.refreshTtlSeconds());
        if (expiry.isAfter(familyExpiry)) expiry = familyExpiry;
        tokens.save(RefreshToken.issue(member, crypto.hash(refresh), family, now, expiry, familyExpiry));
        var providers = identities.findAllByMember_Id(member.getId()).stream().map(i -> i.getProvider().name()).sorted().toList();
        var response = new AuthResponse(access(member, now), "Bearer", policy.accessTtlSeconds(),
                new MemberResponse(member.getId().toString(), member.getEmail(), member.hasPassword(), providers));
        return new IssuedTokens(response, refresh, Math.max(0, Duration.between(now, expiry).toSeconds()));
    }
    /** 기존 Nimbus 라이브러리로 10분 이하의 HS256 Access JWT를 발급한다. */
    private String access(Member member, Instant now) {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .subject(member.getId().toString()).issuer(issuer).audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(policy.accessTtlSeconds())))
                .jwtID(UUID.randomUUID().toString()).build());
        try { jwt.sign(new MACSigner(signingKey.getEncoded())); return jwt.serialize(); }
        catch (JOSEException e) { throw new IllegalStateException("JWT signing failed"); }
    }
}
