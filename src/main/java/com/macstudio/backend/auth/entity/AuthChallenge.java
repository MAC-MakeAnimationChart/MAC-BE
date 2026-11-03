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

/** 인증 기록의 DB 매핑과 상태 규칙. 원문 코드 비교·토큰 생성·트랜잭션은 서비스 책임이다. */
@Entity
@Getter
@Table(name = "auth_challenge", uniqueConstraints =
        @UniqueConstraint(name = "uk_challenge_proof", columnNames = "proof_token_hash"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthChallenge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "challenge_id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_challenge_member"))
    private Member member;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 24)
    private AuthPurpose purpose;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 12)
    private AuthMethod method;

    @Column(name = "target_email", updatable = false, length = 254)
    private String targetEmail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_provider", updatable = false, length = 10)
    private IdentityProvider targetProvider;

    @Column(name = "auth_version", updatable = false)
    private Long authVersion;

    @Getter(AccessLevel.NONE)
    @Column(name = "code_digest", updatable = false, columnDefinition = "BINARY(32)")
    private byte[] codeDigest;

    @Column(name = "attempt_count", nullable = false, columnDefinition = "SMALLINT UNSIGNED")
    private int attemptCount;

    @Column(name = "code_expires_at", updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime codeExpiresAt;

    @Getter(AccessLevel.NONE)
    @Column(name = "proof_token_hash", columnDefinition = "BINARY(32)")
    private byte[] proofTokenHash;

    @Column(name = "proof_expires_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime proofExpiresAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 12)
    private AuthChallengeStatus status;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    /** 정규화된 이메일과 HMAC만 받아 가입 전 인증 기록을 만든다. 회원은 생성하지 않는다. */
    public static AuthChallenge createSignupEmail(String normalizedEmail, byte[] codeDigest,
                                                  Instant now, Instant codeExpiresAt) {
        requireNormalizedEmail(normalizedEmail);
        var challenge = new AuthChallenge();
        challenge.createdAt = utc(now);
        challenge.codeExpiresAt = futureTime(codeExpiresAt, challenge.createdAt);
        challenge.codeDigest = copyDigest(codeDigest);
        challenge.targetEmail = normalizedEmail;
        challenge.purpose = AuthPurpose.SIGNUP;
        challenge.method = AuthMethod.EMAIL_CODE;
        challenge.status = AuthChallengeStatus.PENDING;
        return challenge;
    }

    /** 활성 이메일 회원과 현재 인증 버전에 묶인 비밀번호 재설정 코드 기록을 만든다. */
    public static AuthChallenge createPasswordResetEmail(Member member, byte[] codeDigest,
                                                          Instant now, Instant codeExpiresAt) {
        Objects.requireNonNull(member, "member");
        requireNormalizedEmail(member.getEmail());
        var challenge = new AuthChallenge();
        challenge.member = member;
        challenge.authVersion = member.getAuthVersion();
        challenge.createdAt = utc(now);
        challenge.codeExpiresAt = futureTime(codeExpiresAt, challenge.createdAt);
        challenge.codeDigest = copyDigest(codeDigest);
        challenge.targetEmail = member.getEmail();
        challenge.purpose = AuthPurpose.PASSWORD_RESET;
        challenge.method = AuthMethod.EMAIL_CODE;
        challenge.status = AuthChallengeStatus.PENDING;
        return challenge;
    }

    /** 연결된 소셜 신원 확인 직후 회원 탈퇴에 사용할 짧은 일회성 증명을 만든다. */
    public static AuthChallenge createSocialReauth(Member member, IdentityProvider provider,
                                                    byte[] proofTokenHash, Instant now, Instant proofExpiresAt) {
        var challenge = new AuthChallenge();
        challenge.member = Objects.requireNonNull(member, "member");
        challenge.targetProvider = Objects.requireNonNull(provider, "provider");
        challenge.authVersion = member.getAuthVersion();
        challenge.proofTokenHash = copyDigest(proofTokenHash);
        challenge.createdAt = utc(now);
        challenge.proofExpiresAt = futureTime(proofExpiresAt, challenge.createdAt);
        challenge.purpose = AuthPurpose.ACCOUNT_DELETE;
        challenge.method = AuthMethod.SOCIAL;
        challenge.status = AuthChallengeStatus.VERIFIED;
        return challenge;
    }

    /** 검증 가능한 대기 상태·시간·시도 한도를 검사한다. 해시 비교 전에 서비스에서 호출한다. */
    public void requireCodeVerifiable(Instant now, int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 65535) {
            throw new IllegalArgumentException("Invalid attempt limit");
        }
        LocalDateTime time = utc(now);
        if (status != AuthChallengeStatus.PENDING || method != AuthMethod.EMAIL_CODE
                || time.isBefore(createdAt) || !time.isBefore(codeExpiresAt)
                || attemptCount >= maxAttempts) {
            throw new IllegalStateException("Challenge is not verifiable");
        }
    }

    /** 오입력 횟수를 늘리고 마지막 허용 시도 실패 시 취소한다. 서비스는 실패도 커밋해야 한다. */
    public void recordFailedAttempt(Instant now, int maxAttempts) {
        requireCodeVerifiable(now, maxAttempts);
        attemptCount++;
        if (attemptCount >= maxAttempts) {
            status = AuthChallengeStatus.CANCELLED;
        }
    }

    /** 서비스가 코드 일치를 확인한 뒤 가입 증명의 해시·만료를 한 번만 등록한다. */
    public void markVerified(byte[] proofTokenHash, Instant proofExpiresAt,
                             Instant now, int maxAttempts) {
        requireCodeVerifiable(now, maxAttempts);
        byte[] hash = copyDigest(proofTokenHash);
        LocalDateTime expiresAt = futureTime(proofExpiresAt, utc(now));
        this.proofTokenHash = hash;
        this.proofExpiresAt = expiresAt;
        this.status = AuthChallengeStatus.VERIFIED;
    }

    /** 잠금 조회로 확인한 가입 증명을 소비한다. 회원 생성과 같은 트랜잭션에서 호출해야 한다. */
    public void consumeSignupProof(String normalizedEmail, Instant now) {
        requireNormalizedEmail(normalizedEmail);
        LocalDateTime time = utc(now);
        if (purpose != AuthPurpose.SIGNUP || method != AuthMethod.EMAIL_CODE
                || status != AuthChallengeStatus.VERIFIED
                || !targetEmail.equals(normalizedEmail)
                || time.isBefore(createdAt) || !time.isBefore(proofExpiresAt)) {
            throw new IllegalStateException("Signup proof is not consumable");
        }
        status = AuthChallengeStatus.CONSUMED;
    }

    /** 회원·이메일·인증 버전에 결합된 비밀번호 재설정 증명을 한 번만 소비한다. */
    public void consumePasswordResetProof(Member currentMember, String normalizedEmail, Instant now) {
        Objects.requireNonNull(currentMember, "currentMember");
        requireNormalizedEmail(normalizedEmail);
        LocalDateTime time = utc(now);
        if (purpose != AuthPurpose.PASSWORD_RESET || method != AuthMethod.EMAIL_CODE
                || status != AuthChallengeStatus.VERIFIED || member == null
                || !Objects.equals(member.getId(), currentMember.getId())
                || !Objects.equals(authVersion, currentMember.getAuthVersion())
                || !targetEmail.equals(normalizedEmail)
                || time.isBefore(createdAt) || !time.isBefore(proofExpiresAt)) {
            throw new IllegalStateException("Password reset proof is not consumable");
        }
        status = AuthChallengeStatus.CONSUMED;
    }

    /** 현재 회원·인증 버전에 묶인 소셜 탈퇴 재인증 증명을 한 번만 소비한다. */
    public void consumeSocialWithdrawalProof(Long memberId, Instant now) {
        LocalDateTime time = utc(now);
        if (purpose != AuthPurpose.ACCOUNT_DELETE || method != AuthMethod.SOCIAL
                || status != AuthChallengeStatus.VERIFIED || member == null
                || !Objects.equals(member.getId(), memberId)
                || !Objects.equals(authVersion, member.getAuthVersion())
                || time.isBefore(createdAt) || !time.isBefore(proofExpiresAt)) {
            throw new IllegalStateException("Social reauthentication proof is not consumable");
        }
        status = AuthChallengeStatus.CONSUMED;
    }

    /** 재발송·취소 시 미소비 기록을 무효화한다. 소비 완료 기록은 되돌리지 않는다. */
    public void cancel() {
        if (status == AuthChallengeStatus.PENDING || status == AuthChallengeStatus.VERIFIED) {
            status = AuthChallengeStatus.CANCELLED;
        }
    }

    /** 해시 배열 복사본을 반환하여 외부 변경으로 인증 상태가 변조되는 것을 막는다. */
    public byte[] getCodeDigest() {
        return codeDigest == null ? null : codeDigest.clone();
    }

    /** 증명 해시 복사본만 반환한다. 원문 토큰은 이 엔티티에 보관하지 않는다. */
    public byte[] getProofTokenHash() {
        return proofTokenHash == null ? null : proofTokenHash.clone();
    }

    /** 이미 정규화된 이메일만 허용한다. API 형식 검증·정규화 자체는 DTO/서비스에서 수행한다. */
    private static void requireNormalizedEmail(String email) {
        Objects.requireNonNull(email, "email");
        if (email.isBlank() || email.length() > 254 || !email.equals(email.strip())
                || !email.equals(email.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Expected normalized email");
        }
    }

    /** DB의 BINARY(32)에 맞는 SHA-256 계열 해시인지 확인하고 방어적으로 복사한다. */
    private static byte[] copyDigest(byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("Expected 32-byte digest");
        }
        return digest.clone();
    }

    /** 시각을 UTC 밀리초로 통일하여 DATETIME(3) 저장 전후의 만료 경계를 일치시킨다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }

    /** 밀리초 정밀도에서도 기준 시각보다 뒤인 만료 시각만 허용한다. */
    private static LocalDateTime futureTime(Instant expiry, LocalDateTime reference) {
        LocalDateTime value = utc(expiry);
        if (!value.isAfter(reference)) {
            throw new IllegalArgumentException("Expiry must be in the future");
        }
        return value;
    }
}
