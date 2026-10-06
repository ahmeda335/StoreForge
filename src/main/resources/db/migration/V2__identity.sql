-- Phase 2: identity. Platform users (merchants/staff), refresh tokens, emailed one-time tokens.
-- Users are NOT tenant data (one user can belong to many stores), so these tables have no store_id
-- and no row-level security.

-- citext: case-insensitive text. The UNIQUE constraint on users.email then treats
-- "Alice@x.com" and "alice@x.com" as duplicates even if application normalization were bypassed.
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id                  uuid        PRIMARY KEY,
    email               citext      NOT NULL,
    password_hash       text        NOT NULL,
    status              text        NOT NULL,
    email_verified_at   timestamptz,
    -- Incremented on password change / "log out everywhere"; copied into access tokens.
    security_stamp      integer     NOT NULL DEFAULT 0,
    -- Lockout bookkeeping, updated by atomic SQL (not through the JPA entity).
    failed_login_count  integer     NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_email_length CHECK (length(email) <= 254),
    CONSTRAINT ck_users_failed_login_count CHECK (failed_login_count >= 0)
);

CREATE TABLE refresh_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL,
    family_id   uuid        NOT NULL,
    token_hash  text        NOT NULL,      -- SHA-256 hex; the raw token is never stored
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,               -- set when rotated; presenting it again = reuse
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL,
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

CREATE TABLE email_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL,
    purpose     text        NOT NULL,
    token_hash  text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL,
    CONSTRAINT uq_email_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_email_tokens_purpose CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    CONSTRAINT fk_email_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_email_tokens_user_purpose_unused ON email_tokens (user_id, purpose) WHERE used_at IS NULL;
