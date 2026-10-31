package com.macstudio.backend.member.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
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
        name = "member_consent",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_consent_member_document",
                        columnNames = {
                                "member_id",
                                "document_type",
                                "document_version"
                        }
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consent_id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_consent_member")
    )
    private Member member;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
            name = "document_type",
            nullable = false,
            updatable = false,
            length = 16
    )
    private ConsentDocumentType documentType;

    @Column(
            name = "document_version",
            nullable = false,
            updatable = false,
            length = 40
    )
    private String documentVersion;

    @Column(
            name = "agreed_at",
            nullable = false,
            updatable = false,
            columnDefinition = "DATETIME(3)"
    )
    private LocalDateTime agreedAt;

    private MemberConsent(
            Member member,
            ConsentDocumentType documentType,
            String documentVersion
    ) {
        this.member = Objects.requireNonNull(member, "member");
        this.documentType =
                Objects.requireNonNull(documentType, "documentType");
        this.documentVersion =
                Objects.requireNonNull(documentVersion, "documentVersion");

        if (documentVersion.isBlank() || documentVersion.length() > 40) {
            throw new IllegalArgumentException(
                    "Invalid consent document version"
            );
        }
    }

    public static MemberConsent agree(
            Member member,
            ConsentDocumentType documentType,
            String documentVersion
    ) {
        return new MemberConsent(member, documentType, documentVersion);
    }

    @PrePersist
    private void prePersist() {
        this.agreedAt = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }
}