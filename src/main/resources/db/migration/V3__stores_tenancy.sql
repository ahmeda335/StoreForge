-- Phase 3: stores (= tenants), staff, roles, invitations, audit log, row-level security.
--
-- Two kinds of tables (ADR-009):
--   TENANT DIRECTORY (no RLS): stores, store_domains, store_members. They answer "which tenant is
--     this?" and "is this user a member?", questions asked BEFORE a tenant context can exist.
--     Only the stores module touches them.
--   TENANT DATA (RLS enforced): roles, invitations, audit_log, and every future business table.
--     Rows are visible only when app.current_store_id is set to their store_id for the transaction.

-- The tenant context for the current transaction (NULL when unset). NULLIF handles the fact that
-- once a custom setting has been used in a session, it reads as '' (not NULL) after the
-- transaction that SET LOCAL it ends, and ''::uuid would be an error.
CREATE FUNCTION current_store_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.current_store_id', true), '')::uuid $$;

-- ---------------------------------------------------------------- tenant directory
CREATE TABLE stores (
    id          uuid        PRIMARY KEY,
    handle      citext      NOT NULL,
    name        text        NOT NULL,
    status      text        NOT NULL,
    currency    char(3)     NOT NULL,
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL,
    CONSTRAINT uq_stores_handle UNIQUE (handle),
    CONSTRAINT ck_stores_handle_format CHECK (handle ~ '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$'),
    CONSTRAINT ck_stores_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_stores_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_stores_name_length CHECK (length(name) BETWEEN 1 AND 100),
    CONSTRAINT fk_stores_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE TABLE store_domains (
    id          uuid        PRIMARY KEY,
    store_id    uuid        NOT NULL,
    hostname    citext      NOT NULL,
    is_primary  boolean     NOT NULL,
    verified_at timestamptz,
    created_at  timestamptz NOT NULL,
    CONSTRAINT uq_store_domains_hostname UNIQUE (hostname),
    CONSTRAINT fk_store_domains_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_store_domains_one_primary ON store_domains (store_id) WHERE is_primary;

-- ---------------------------------------------------------------- tenant data: roles
CREATE TABLE roles (
    id          uuid        PRIMARY KEY,
    store_id    uuid        NOT NULL,
    name        text        NOT NULL,
    is_owner    boolean     NOT NULL,
    permissions text[]      NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL,
    CONSTRAINT uq_roles_store_name UNIQUE (store_id, name),
    -- Target of composite foreign keys: a membership can only reference a role of ITS OWN store.
    CONSTRAINT uq_roles_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_roles_name_length CHECK (length(name) BETWEEN 1 AND 50),
    CONSTRAINT fk_roles_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_roles_one_owner_role ON roles (store_id) WHERE is_owner;

-- ---------------------------------------------------------------- tenant directory: members
CREATE TABLE store_members (
    store_id    uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    role_id     uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT pk_store_members PRIMARY KEY (store_id, user_id),
    CONSTRAINT fk_store_members_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE,
    CONSTRAINT fk_store_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- Composite FK: (store_id, role_id) must exist in roles. Prevents assigning store B's role in store A.
    CONSTRAINT fk_store_members_role FOREIGN KEY (store_id, role_id) REFERENCES roles (store_id, id)
);
CREATE INDEX ix_store_members_user ON store_members (user_id);
CREATE INDEX ix_store_members_role ON store_members (store_id, role_id);

-- ---------------------------------------------------------------- tenant data: invitations
CREATE TABLE invitations (
    id          uuid        PRIMARY KEY,
    store_id    uuid        NOT NULL,
    email       citext      NOT NULL,
    role_id     uuid        NOT NULL,
    token_hash  text        NOT NULL,
    invited_by  uuid        NOT NULL,
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    accepted_by uuid,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL,
    CONSTRAINT uq_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_invitations_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE,
    CONSTRAINT fk_invitations_role FOREIGN KEY (store_id, role_id) REFERENCES roles (store_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id)
);
-- At most one pending invitation per email per store.
CREATE UNIQUE INDEX uq_invitations_pending_email ON invitations (store_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- ---------------------------------------------------------------- tenant data: audit log
CREATE TABLE audit_log (
    id            uuid        PRIMARY KEY,
    store_id      uuid        NOT NULL,
    actor_type    text        NOT NULL,
    actor_id      uuid,
    action        text        NOT NULL,
    resource_type text        NOT NULL,
    resource_id   text,
    details       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    request_id    text,
    occurred_at   timestamptz NOT NULL,
    CONSTRAINT ck_audit_log_actor_type CHECK (actor_type IN ('USER', 'API_KEY', 'APP', 'SYSTEM', 'ADMIN')),
    CONSTRAINT fk_audit_log_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
CREATE INDEX ix_audit_log_store_time ON audit_log (store_id, occurred_at DESC, id DESC);
-- Append-only for the application: history cannot be rewritten even by a bug or injection.
REVOKE UPDATE, DELETE ON audit_log FROM ${app_role};

-- ---------------------------------------------------------------- row-level security
-- ENABLE turns policies on; FORCE applies them to the table owner too (defense in depth, e.g.
-- if someone ever connects the app as the owner). USING filters reads/updates/deletes;
-- WITH CHECK rejects inserting or updating a row into ANOTHER tenant.
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invitations
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
