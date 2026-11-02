package com.macstudio.backend.member.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "member_identity", uniqueConstraints = {
        @UniqueConstraint(name = "uk_identity_provider_subject",
                columnNames = {"provider", "provider_subject"}),
        @UniqueConstraint(name = "uk_identity_member_provider",
                columnNames = {"member_id", "provider"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "identity_id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_identity_member"))
    private Member member;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "provider", nullable = false, updatable = false, length = 10)
    private IdentityProvider provider;

    @Column(name = "provider_subject", nullable = false, updatable = false, length = 255)
    private String providerSubject;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    /** 필수 연결 정보와 식별자 길이를 검사하여 임의 변경이 불가능한 연결을 생성한다. */
    private MemberIdentity(Member member, IdentityProvider provider, String providerSubject) {
        this.member = Objects.requireNonNull(member, "member");
        this.provider = Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerSubject, "providerSubject");
        if (providerSubject.isBlank()
                || providerSubject.codePointCount(0, providerSubject.length()) > 255) {
            throw new IllegalArgumentException("Invalid provider subject");
        }
        // 제공사 식별자는 대소문자와 끝 공백을 그대로 보존한다.
        this.providerSubject = providerSubject;
    }

    /** 서비스가 제공사 신원과 연결 권한을 검증한 후 저장할 연결 엔티티를 만든다. */
    public static MemberIdentity create(Member member, IdentityProvider provider,
                                        String verifiedProviderSubject) {
        return new MemberIdentity(member, provider, verifiedProviderSubject);
    }

    /** 최초 저장 시 생성 시각을 UTC 밀리초로 기록한다. */
    @PrePersist
    private void prePersist() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
