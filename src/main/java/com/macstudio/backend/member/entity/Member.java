package com.macstudio.backend.member.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Locale;

@Getter
@Entity
@Table(
        name = "member",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_member_email",
                        columnNames = "email"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id", nullable = false)
    private Long id;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @Column(name = "withdrawn_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime withdrawnAt;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false,
            columnDefinition = "DATETIME(3)"
    )
    private LocalDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false,
            columnDefinition = "DATETIME(3)"
    )
    private LocalDateTime updatedAt;

    /** 인증·동의 완료 후 사용할 회원의 초기 상태와 인증 버전을 설정한다. */
    private Member(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = MemberStatus.ACTIVE;
        this.authVersion = 0L;
    }

    /** 저장 가능한 길이이며 이미 정규화된 이메일인지 확인한다. */
    private static void validateNormalizedEmail(String email) {
        Objects.requireNonNull(email, "email");

        if (email.isBlank()
                || email.length() > 254
                || !email.equals(email.strip())
                || !email.equals(email.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Expected normalized email"
            );
        }
    }

    /** 인증·동의가 확인된 가입 정보로 이메일 회원을 생성한다. */
    public static Member createEmailMember(
            String normalizedEmail,
            String encodedPassword
    ) {
        validateNormalizedEmail(normalizedEmail);
        Objects.requireNonNull(encodedPassword, "encodedPassword");

        if (encodedPassword.isBlank() || encodedPassword.length() > 255) {
            throw new IllegalArgumentException(
                    "Invalid encoded password"
            );
        }

        return new Member(normalizedEmail, encodedPassword);
    }

    /** 제공사에서 검증한 이메일로 소셜 회원을 생성한다. 이메일이 없으면 null을 허용한다. */
    public static Member createSocialMember(String normalizedVerifiedEmail) {
        if (normalizedVerifiedEmail != null) {
            validateNormalizedEmail(normalizedVerifiedEmail);
        }

        return new Member(normalizedVerifiedEmail, null);
    }

    /** 현재 회원이 서비스 이용 가능한 ACTIVE 상태인지 확인한다. */
    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    /** 탈퇴 절차를 시작하여 회원을 이용 불가 상태로 전환한다. 반복 호출은 허용한다. */
    public void beginDeletion() {
        if (status == MemberStatus.DELETING || status == MemberStatus.WITHDRAWN) {
            return;
        }

        status = MemberStatus.DELETING;
    }


    /** 데이터 정리 이후 회원 행과 이메일을 보존한 채 탈퇴 완료 상태로 전환한다. */
    public void completeWithdrawal(java.time.Instant withdrawnAt) {
        Objects.requireNonNull(withdrawnAt, "withdrawnAt");
        if (status != MemberStatus.DELETING) {
            throw new IllegalStateException("Withdrawal must begin before completion");
        }
        status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = LocalDateTime.ofInstant(withdrawnAt, ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        authVersion++;
    }

    /** 이메일 로그인에 사용할 비밀번호 해시가 등록되어 있는지 확인한다. */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    /** 이메일 인증이 끝난 활성 이메일 회원의 비밀번호를 교체하고 이전 인증 증명을 무효화한다. */
    public void resetPassword(String encodedPassword) {
        Objects.requireNonNull(encodedPassword, "encodedPassword");
        if (!isActive() || !hasPassword() || encodedPassword.isBlank() || encodedPassword.length() > 255) {
            throw new IllegalStateException("Password cannot be reset for this member");
        }
        passwordHash = encodedPassword;
        authVersion++;
    }

    /** 최초 저장 시 생성·수정 시각을 동일한 UTC 밀리초로 기록한다. */
    @PrePersist
    private void prePersist() {
        LocalDateTime now = utcNow();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 변경 사항 저장 직전에 수정 시각을 갱신한다. */
    @PreUpdate
    private void preUpdate() {
        this.updatedAt = utcNow();
    }

    /** DB DATETIME(3)에 맞춰 UTC 현재 시각을 밀리초 정밀도로 반환한다. */
    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }
}
