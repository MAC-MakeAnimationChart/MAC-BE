package com.macstudio.backend.auth.entity;

import com.macstudio.backend.member.entity.Member;
import jakarta.persistence.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.*;

/** 로그인별 family와 Refresh 해시를 보관한다. 원문 토큰은 저장하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "refresh_token", uniqueConstraints = @UniqueConstraint(name = "uk_refresh_hash", columnNames = "token_hash"))
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "token_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false) private Member member;
    @Getter(AccessLevel.NONE) @Column(name = "token_hash", nullable = false, updatable = false, columnDefinition = "BINARY(32)") private byte[] tokenHash;
    @Getter(AccessLevel.NONE) @Column(name = "family_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)") private byte[] familyId;
    @Column(name = "issued_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)") private LocalDateTime issuedAt;
    @Column(name = "expires_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)") private LocalDateTime expiresAt;
    @Column(name = "family_expires_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)") private LocalDateTime familyExpiresAt;
    @Column(name = "revoked_at", columnDefinition = "DATETIME(3)") private LocalDateTime revokedAt;

    /** 발급 시각과 family 절대 기한 안에서 새 토큰 기록을 만든다. */
    public static RefreshToken issue(Member member, byte[] hash, byte[] family, Instant now, Instant expiry, Instant familyExpiry) {
        if (member == null || hash == null || hash.length != 32 || family == null || family.length != 16)
            throw new IllegalArgumentException("Invalid refresh metadata");
        var token = new RefreshToken();
        token.member = member; token.tokenHash = hash.clone(); token.familyId = family.clone();
        token.issuedAt = utc(now); token.expiresAt = utc(expiry); token.familyExpiresAt = utc(familyExpiry);
        if (!token.expiresAt.isAfter(token.issuedAt) || token.expiresAt.isAfter(token.familyExpiresAt))
            throw new IllegalArgumentException("Invalid refresh expiry");
        return token;
    }
    /** 회전·로그아웃으로 폐기하며 반복 호출은 기존 폐기 시각을 유지한다. */
    public void revoke(Instant now) { if (revokedAt == null) revokedAt = utc(now).isBefore(issuedAt) ? issuedAt : utc(now); }
    /** 폐기되지 않았고 토큰 및 family가 모두 유효한지 검사한다. */
    public boolean isUsable(Instant now) { var time = utc(now); return revokedAt == null && !time.isBefore(issuedAt) && time.isBefore(expiresAt) && time.isBefore(familyExpiresAt); }
    /** family 식별자를 외부에서 변경할 수 없도록 복사한다. */
    public byte[] getFamilyId() { return familyId.clone(); }
    /** UTC 밀리초로 DB 시각을 통일한다. */
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }
}
