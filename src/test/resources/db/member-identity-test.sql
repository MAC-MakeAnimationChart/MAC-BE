-- 로컬 mac_test 전용 fixture. 기존 테이블을 변경/삭제하지 않으며 정식 migration이 아니다.

SET SESSION time_zone = '+00:00';

CREATE TABLE IF NOT EXISTS member (
    member_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) COLLATE utf8mb4_0900_as_ci NULL,
    password_hash VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    auth_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (member_id),
    CONSTRAINT uk_member_email UNIQUE (email),
    CONSTRAINT ck_member_status CHECK (status IN ('ACTIVE', 'DELETING')),
    CONSTRAINT ck_member_password_email CHECK (password_hash IS NULL OR email IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS member_identity (
    identity_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    provider VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_subject VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (identity_id),
    CONSTRAINT uk_identity_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uk_identity_member_provider UNIQUE (member_id, provider),
    CONSTRAINT ck_identity_provider CHECK (provider IN ('GOOGLE', 'KAKAO', 'NAVER')),
    CONSTRAINT fk_identity_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS member_consent (
    consent_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    document_type VARCHAR(16) NOT NULL,
    document_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agreed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consent_id),
    CONSTRAINT uk_consent_member_document UNIQUE (member_id, document_type, document_version),
    CONSTRAINT ck_consent_document CHECK (document_type IN ('TERMS', 'PRIVACY')),
    CONSTRAINT fk_consent_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS auth_challenge (
    challenge_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NULL,
    purpose VARCHAR(24) NOT NULL,
    method VARCHAR(12) NOT NULL,
    target_email VARCHAR(254) COLLATE utf8mb4_0900_as_ci NULL,
    target_provider VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    auth_version BIGINT UNSIGNED NULL,
    code_digest BINARY(32) NULL COMMENT 'HMAC-SHA-256; secret key is outside DB',
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    code_expires_at DATETIME(3) NULL,
    proof_token_hash BINARY(32) NULL COMMENT 'SHA-256 of random verification/reauth token',
    proof_expires_at DATETIME(3) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (challenge_id),
    CONSTRAINT uk_challenge_proof UNIQUE (proof_token_hash),
    KEY idx_challenge_member (member_id, purpose, status),
    KEY idx_challenge_email (target_email, purpose, created_at),
    KEY idx_challenge_code_expiry (code_expires_at),
    KEY idx_challenge_proof_expiry (proof_expires_at),
    KEY idx_challenge_created (created_at),
    CONSTRAINT ck_challenge_purpose CHECK (purpose IN (
        'SIGNUP', 'PASSWORD_RESET', 'EMAIL_CHANGE', 'PASSWORD_SET',
        'PASSWORD_CHANGE', 'IDENTITY_LINK', 'IDENTITY_UNLINK', 'ACCOUNT_DELETE')),
    CONSTRAINT ck_challenge_method CHECK (method IN ('EMAIL_CODE', 'PASSWORD', 'SOCIAL')),
    CONSTRAINT ck_challenge_provider CHECK (
        target_provider IS NULL OR target_provider IN ('GOOGLE', 'KAKAO', 'NAVER')),
    CONSTRAINT ck_challenge_status CHECK (status IN ('PENDING', 'VERIFIED', 'CONSUMED', 'CANCELLED')),
    CONSTRAINT ck_challenge_code CHECK (
        (method = 'EMAIL_CODE' AND target_email IS NOT NULL
            AND code_digest IS NOT NULL AND code_expires_at IS NOT NULL)
        OR (method IN ('PASSWORD', 'SOCIAL') AND code_digest IS NULL AND code_expires_at IS NULL)),
    CONSTRAINT ck_challenge_proof_pair CHECK (
        (proof_token_hash IS NULL AND proof_expires_at IS NULL)
        OR (proof_token_hash IS NOT NULL AND proof_expires_at IS NOT NULL)),
    CONSTRAINT ck_challenge_verified_proof CHECK (
        status NOT IN ('VERIFIED', 'CONSUMED') OR proof_token_hash IS NOT NULL),
    CONSTRAINT ck_challenge_code_time CHECK (code_expires_at IS NULL OR code_expires_at > created_at),
    CONSTRAINT ck_challenge_proof_time CHECK (proof_expires_at IS NULL OR proof_expires_at > created_at),
    CONSTRAINT fk_challenge_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
