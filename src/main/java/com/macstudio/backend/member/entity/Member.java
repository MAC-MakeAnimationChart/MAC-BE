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

    private Member(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = MemberStatus.ACTIVE;
        this.authVersion = 0L;
    }

    /*createdEmailMember(): 정규화된 이메일과 이미 해시 처리된 비밀번호로 생성*/
    public static Member createEmailMember(
            String normalizedEmail,
            String encodedPassword
    ) {
        /*email과 passwordHash의 Null 허용 => DB의 소셜 전용 회원 구조와 맞춤*/
        Objects.requireNonNull(normalizedEmail, "normalizedEmail");
        Objects.requireNonNull(encodedPassword, "encodedPassword");

        if (normalizedEmail.isBlank() || encodedPassword.isBlank()) {
            throw new IllegalArgumentException(
                    "Email and encoded password must not be blank"
            );
        }

        return new Member(normalizedEmail, encodedPassword);
    }

    /*가입 서비스가 인증 약관을 검증한 뒤 생성한다는 전제*/
    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    @PrePersist
    private void prePersist() {
        LocalDateTime now = utcNow();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = utcNow();
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }
}