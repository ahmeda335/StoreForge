-- Phase 14: platform administrators (StoreForge staff) with mandatory TOTP, and their audit log.
-- Platform tables: no store_id, no RLS (like the identity tables).

CREATE TABLE platform_admins (
    user_id               uuid        PRIMARY KEY,
    granted_at            timestamptz NOT NULL DEFAULT now(),
    note                  text,
    -- TOTP secret sealed with AES-GCM (SecretBox, key outside the database, user id as associated data).
    totp_secret           text,
    totp_confirmed_at     timestamptz,
    -- Last accepted 30-second time step: a code is accepted once, never again (replay).
    totp_last_step        bigint,
    -- SHA-256 of the unused one-time recovery codes.
    recovery_code_hashes  text[]      NOT NULL DEFAULT '{}',
    CONSTRAINT ck_platform_admins_confirmed_has_secret CHECK (totp_confirmed_at IS NULL OR totp_secret IS NOT NULL),
    CONSTRAINT fk_platform_admins_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
-- Nobody becomes a platform admin through the application, not even through a bug in it: the app
-- role can't insert or delete rows. Operators grant and revoke as the owner role (runbook).
REVOKE INSERT, DELETE ON platform_admins FROM ${app_role};

CREATE TABLE platform_audit_log (
    id             uuid        PRIMARY KEY,
    admin_user_id  uuid        NOT NULL,
    action         text        NOT NULL,
    target_type    text,
    target_id      text,
    details        jsonb       NOT NULL DEFAULT '{}',
    client_ip      text,
    occurred_at    timestamptz NOT NULL
);
CREATE INDEX ix_platform_audit_log_time ON platform_audit_log (occurred_at DESC, id DESC);
REVOKE UPDATE, DELETE ON platform_audit_log FROM ${app_role};
