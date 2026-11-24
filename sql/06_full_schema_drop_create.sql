-- DESTRUCTIVE MANUAL SCRIPT: drops all MAC Studio business tables and data.
-- Fresh local schema only. Review and back up the mac database before execution.
-- MySQL 8.0.16+ / 8.4. Not a Flyway migration and never run automatically.

USE mac;
SET SESSION time_zone = '+00:00';

-- Drop children before parents so foreign-key checks can remain enabled.
DROP TABLE IF EXISTS oauth_transaction;
DROP TABLE IF EXISTS refresh_token;
DROP TABLE IF EXISTS member_consent;
DROP TABLE IF EXISTS member_identity;
DROP TABLE IF EXISTS auth_challenge;
DROP TABLE IF EXISTS chart;
DROP TABLE IF EXISTS dataset;
DROP TABLE IF EXISTS member;

CREATE TABLE member (
    member_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) COLLATE utf8mb4_0900_as_ci NULL,
    password_hash VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    auth_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    withdrawn_at DATETIME(3) NULL,
    purge_due_at DATETIME(3)
        GENERATED ALWAYS AS (DATE_ADD(withdrawn_at, INTERVAL 3 MONTH)) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (member_id),
    CONSTRAINT uk_member_email UNIQUE (email),
    KEY idx_member_purge_due (status, purge_due_at, member_id),
    CONSTRAINT ck_member_status
        CHECK (status IN ('ACTIVE', 'DELETING', 'WITHDRAWN')),
    CONSTRAINT ck_member_password_email
        CHECK (password_hash IS NULL OR email IS NOT NULL),
    CONSTRAINT ck_member_withdrawn CHECK (
        (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL)
        OR (status <> 'WITHDRAWN' AND withdrawn_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE dataset (
    dataset_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    creation_request_id BINARY(16) NOT NULL,
    name VARCHAR(120) NOT NULL,
    data_schema_version SMALLINT UNSIGNED NOT NULL,
    data_json JSON NOT NULL,
    data_size_bytes BIGINT UNSIGNED NOT NULL,
    row_count INT UNSIGNED NOT NULL,
    column_count INT UNSIGNED NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (dataset_id),
    CONSTRAINT uk_dataset_member_name UNIQUE (member_id, name),
    CONSTRAINT uk_dataset_member_creation_request
        UNIQUE (member_id, creation_request_id),
    KEY idx_dataset_member_updated (member_id, updated_at),
    KEY idx_dataset_cleanup_updated (updated_at, dataset_id),
    CONSTRAINT ck_dataset_schema_version
        CHECK (data_schema_version >= 1),
    CONSTRAINT ck_dataset_revision
        CHECK (revision >= 1),
    CONSTRAINT fk_dataset_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE chart (
    chart_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    dataset_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(255) NOT NULL DEFAULT '',
    chart_type VARCHAR(30) NOT NULL,
    figure_schema_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    figure_json JSON NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    thumbnail_key VARCHAR(512) NULL,
    thumbnail_size_bytes BIGINT UNSIGNED NULL,
    thumbnail_content_type VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    thumbnail_chart_revision BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (chart_id),
    KEY idx_chart_dataset_updated (dataset_id, updated_at),
    CONSTRAINT ck_chart_schema_version
        CHECK (figure_schema_version >= 1),
    CONSTRAINT ck_chart_revision
        CHECK (revision >= 1),
    CONSTRAINT ck_chart_thumbnail_metadata CHECK (
        (
            thumbnail_key IS NULL
            AND thumbnail_size_bytes IS NULL
            AND thumbnail_content_type IS NULL
            AND thumbnail_chart_revision IS NULL
        )
        OR
        (
            thumbnail_key IS NOT NULL
            AND thumbnail_size_bytes IS NOT NULL
            AND thumbnail_content_type IS NOT NULL
            AND thumbnail_chart_revision = revision
        )
    ),
    CONSTRAINT fk_chart_dataset FOREIGN KEY (dataset_id)
        REFERENCES dataset (dataset_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE member_identity (
    identity_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    provider VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_subject VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (identity_id),
    CONSTRAINT uk_identity_provider_subject
        UNIQUE (provider, provider_subject),
    CONSTRAINT uk_identity_member_provider UNIQUE (member_id, provider),
    CONSTRAINT ck_identity_provider
        CHECK (provider IN ('GOOGLE', 'KAKAO', 'NAVER')),
    CONSTRAINT fk_identity_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- SHA-256 and HMAC-SHA-256 digests are stored as raw 32-byte values.
CREATE TABLE auth_challenge (
    challenge_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NULL,
    purpose VARCHAR(24) NOT NULL,
    method VARCHAR(12) NOT NULL,
    target_email VARCHAR(254) COLLATE utf8mb4_0900_as_ci NULL,
    target_provider VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    auth_version BIGINT UNSIGNED NULL,
    code_digest BINARY(32) NULL
        COMMENT 'HMAC-SHA-256; secret key is outside DB',
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    code_expires_at DATETIME(3) NULL,
    proof_token_hash BINARY(32) NULL
        COMMENT 'SHA-256 of random verification/reauth token',
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
        'PASSWORD_CHANGE', 'IDENTITY_LINK', 'IDENTITY_UNLINK',
        'ACCOUNT_DELETE'
    )),
    CONSTRAINT ck_challenge_method
        CHECK (method IN ('EMAIL_CODE', 'PASSWORD', 'SOCIAL')),
    CONSTRAINT ck_challenge_provider CHECK (
        target_provider IS NULL
        OR target_provider IN ('GOOGLE', 'KAKAO', 'NAVER')
    ),
    CONSTRAINT ck_challenge_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'CONSUMED', 'CANCELLED')),
    CONSTRAINT ck_challenge_code CHECK (
        (
            method = 'EMAIL_CODE'
            AND target_email IS NOT NULL
            AND code_digest IS NOT NULL
            AND code_expires_at IS NOT NULL
        )
        OR
        (
            method IN ('PASSWORD', 'SOCIAL')
            AND code_digest IS NULL
            AND code_expires_at IS NULL
        )
    ),
    CONSTRAINT ck_challenge_proof_pair CHECK (
        (proof_token_hash IS NULL AND proof_expires_at IS NULL)
        OR (proof_token_hash IS NOT NULL AND proof_expires_at IS NOT NULL)
    ),
    CONSTRAINT ck_challenge_verified_proof CHECK (
        status NOT IN ('VERIFIED', 'CONSUMED')
        OR proof_token_hash IS NOT NULL
    ),
    CONSTRAINT ck_challenge_code_time CHECK (
        code_expires_at IS NULL OR code_expires_at > created_at
    ),
    CONSTRAINT ck_challenge_proof_time CHECK (
        proof_expires_at IS NULL OR proof_expires_at > created_at
    ),
    CONSTRAINT fk_challenge_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE refresh_token (
    token_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    token_hash BINARY(32) NOT NULL
        COMMENT 'SHA-256 of random refresh token',
    family_id BINARY(16) NOT NULL
        COMMENT 'Random identifier per login, retained across rotation',
    issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    family_expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY (token_id),
    CONSTRAINT uk_refresh_hash UNIQUE (token_hash),
    KEY idx_refresh_member (member_id),
    KEY idx_refresh_family (family_id, revoked_at),
    KEY idx_refresh_cleanup (family_expires_at),
    CONSTRAINT ck_refresh_expiry CHECK (
        expires_at > issued_at AND expires_at <= family_expires_at
    ),
    CONSTRAINT ck_refresh_revoked CHECK (
        revoked_at IS NULL OR revoked_at >= issued_at
    ),
    CONSTRAINT fk_refresh_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Provider authorization codes, access tokens, state, PKCE verifiers, and
-- client secrets are deliberately not persisted in this table.
CREATE TABLE oauth_transaction (
    transaction_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    provider VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_subject VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    verified_email VARCHAR(254) COLLATE utf8mb4_0900_as_ci NULL,
    intent VARCHAR(10) NOT NULL,
    member_id BIGINT UNSIGNED NULL,
    challenge_id BIGINT UNSIGNED NULL,
    signup_token_hash BINARY(32) NULL,
    signup_expires_at DATETIME(3) NULL,
    signup_consumed_at DATETIME(3) NULL,
    exchange_completed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (transaction_id),
    CONSTRAINT uk_oauth_challenge UNIQUE (challenge_id),
    CONSTRAINT uk_oauth_signup_token UNIQUE (signup_token_hash),
    KEY idx_oauth_member (member_id),
    KEY idx_oauth_signup_expiry (signup_expires_at),
    CONSTRAINT ck_oauth_provider
        CHECK (provider IN ('GOOGLE', 'KAKAO', 'NAVER')),
    CONSTRAINT ck_oauth_intent CHECK (intent IN ('LOGIN', 'REAUTH')),
    CONSTRAINT ck_oauth_exchange_time
        CHECK (exchange_completed_at >= created_at),
    CONSTRAINT ck_oauth_signup_pair CHECK (
        (
            signup_token_hash IS NULL
            AND signup_expires_at IS NULL
            AND signup_consumed_at IS NULL
        )
        OR
        (
            signup_token_hash IS NOT NULL
            AND signup_expires_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_oauth_flow CHECK (
        (
            intent = 'LOGIN'
            AND challenge_id IS NULL
            AND (
                (member_id IS NOT NULL AND signup_token_hash IS NULL)
                OR (member_id IS NULL AND signup_token_hash IS NOT NULL)
            )
        )
        OR
        (
            intent = 'REAUTH'
            AND member_id IS NOT NULL
            AND challenge_id IS NOT NULL
            AND signup_token_hash IS NULL
        )
    ),
    CONSTRAINT ck_oauth_signup_expiry CHECK (
        signup_expires_at IS NULL
        OR signup_expires_at > exchange_completed_at
    ),
    CONSTRAINT ck_oauth_signup_consumed CHECK (
        signup_consumed_at IS NULL
        OR (
            signup_token_hash IS NOT NULL
            AND signup_expires_at IS NOT NULL
            AND signup_consumed_at >= exchange_completed_at
            AND signup_consumed_at < signup_expires_at
        )
    ),
    CONSTRAINT fk_oauth_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE,
    CONSTRAINT fk_oauth_challenge FOREIGN KEY (challenge_id)
        REFERENCES auth_challenge (challenge_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE member_consent (
    consent_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id BIGINT UNSIGNED NOT NULL,
    document_type VARCHAR(16) NOT NULL,
    document_version VARCHAR(40)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agreed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consent_id),
    CONSTRAINT uk_consent_member_document
        UNIQUE (member_id, document_type, document_version),
    CONSTRAINT ck_consent_document
        CHECK (document_type IN ('TERMS', 'PRIVACY')),
    CONSTRAINT fk_consent_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

SHOW CREATE TABLE member;
SHOW CREATE TABLE dataset;
SHOW CREATE TABLE chart;
SHOW CREATE TABLE member_identity;
SHOW CREATE TABLE auth_challenge;
SHOW CREATE TABLE refresh_token;
SHOW CREATE TABLE oauth_transaction;
SHOW CREATE TABLE member_consent;
