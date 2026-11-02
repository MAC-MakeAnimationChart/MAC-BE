package com.macstudio.backend.auth.entity;

import com.macstudio.backend.member.entity.IdentityProvider;
import com.macstudio.backend.member.entity.Member;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 제공사 code 교환 결과와 신규 가입 일회성 증명만 저장한다. code/token/verifier는 저장하지 않는다. */
@Entity
@Getter
@Table(name = "oauth_transaction", uniqueConstraints = {
        @UniqueConstraint(name = "uk_oauth_challenge", columnNames = "challenge_id"),
        @UniqueConstraint(name = "uk_oauth_signup_token", columnNames = "signup_token_hash")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private IdentityProvider provider;

    @Column(name = "provider_subject", nullable = false, updatable = false, length = 255)
    private String providerSubject;

    @Column(name = "verified_email", updatable = false, length = 254)
    private String verifiedEmail;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private OAuthIntent intent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", updatable = false, foreignKey = @ForeignKey(name = "fk_oauth_member"))
    private Member member;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", updatable = false, foreignKey = @ForeignKey(name = "fk_oauth_challenge"))
    private AuthChallenge challenge;

    @Getter(AccessLevel.NONE)
    @Column(name = "signup_token_hash", columnDefinition = "BINARY(32)")
    private byte[] signupTokenHash;

    @Column(name = "signup_expires_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime signupExpiresAt;

    @Column(name = "signup_consumed_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime signupConsumedAt;

    @Column(name = "exchange_completed_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime exchangeCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    /** 신규 소셜 신원을 약관 동의 전 임시 가입 증명으로 기록한다. */
    public static OAuthTransaction pendingSignup(IdentityProvider provider, String subject, String email,
                                                   byte[] tokenHash, Instant now, Instant expiresAt) {
        OAuthTransaction value = base(provider, subject, email, OAuthIntent.LOGIN, now);
        value.signupTokenHash = digest(tokenHash);
        value.signupExpiresAt = future(expiresAt, value.createdAt);
        return value;
    }

    /** 이미 연결된 회원의 로그인 code 교환 성공 기록을 남긴다. */
    public static OAuthTransaction login(IdentityProvider provider, String subject, String email,
                                          Member member, Instant now) {
        OAuthTransaction value = base(provider, subject, email, OAuthIntent.LOGIN, now);
        value.member = Objects.requireNonNull(member, "member");
        return value;
    }

    /** 민감 작업 재인증 결과를 회원·challenge에 묶어 기록한다. */
    public static OAuthTransaction reauth(IdentityProvider provider, String subject, String email,
                                           Member member, AuthChallenge challenge, Instant now) {
        OAuthTransaction value = base(provider, subject, email, OAuthIntent.REAUTH, now);
        value.member = Objects.requireNonNull(member, "member");
        value.challenge = Objects.requireNonNull(challenge, "challenge");
        return value;
    }

    /** 가입 증명을 잠금 상태에서 한 번만 소비한다. */
    public void consumeSignup(Instant now) {
        LocalDateTime time = utc(now);
        if (intent != OAuthIntent.LOGIN || signupTokenHash == null || signupConsumedAt != null
                || time.isBefore(createdAt) || !time.isBefore(signupExpiresAt)) {
            throw new IllegalStateException("Social signup proof is not consumable");
        }
        signupConsumedAt = time;
    }

    public byte[] getSignupTokenHash() { return signupTokenHash == null ? null : signupTokenHash.clone(); }

    private static OAuthTransaction base(IdentityProvider provider, String subject, String email,
                                          OAuthIntent intent, Instant now) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank() || subject.codePointCount(0, subject.length()) > 255)
            throw new IllegalArgumentException("Invalid provider subject");
        if (email != null && (email.isBlank() || email.length() > 254 || !email.equals(email.strip())
                || !email.equals(email.toLowerCase(Locale.ROOT))))
            throw new IllegalArgumentException("Expected normalized email");
        OAuthTransaction value = new OAuthTransaction();
        value.provider = provider;
        value.providerSubject = subject;
        value.verifiedEmail = email;
        value.intent = intent;
        value.exchangeCompletedAt = utc(now);
        value.createdAt = value.exchangeCompletedAt;
        return value;
    }

    private static byte[] digest(byte[] value) {
        if (value == null || value.length != 32) throw new IllegalArgumentException("Expected 32-byte digest");
        return value.clone();
    }
    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(value), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
    private static LocalDateTime future(Instant value, LocalDateTime reference) {
        LocalDateTime result = utc(value);
        if (!result.isAfter(reference)) throw new IllegalArgumentException("Expiry must be in the future");
        return result;
    }
}
