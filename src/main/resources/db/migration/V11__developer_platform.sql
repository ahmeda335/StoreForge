-- Phase 12a: credentials for the developer API (API keys now, app installations in 12b), usage
-- statistics, app-owned webhook subscriptions, and the indexes behind "changed since" sync queries.

-- DIRECTORY table (no RLS, like store_members, ADR-009): a request arrives with only a token, and
-- the credential row is what TELLS us the store. Only the developer module's authentication reads it.
CREATE TABLE api_credentials (
    id              uuid        PRIMARY KEY,
    store_id        uuid        NOT NULL,
    kind            text        NOT NULL,
    name            text        NOT NULL,
    -- First characters of the token, safe to display ("sfk_Xy12…") so merchants can tell keys apart.
    token_prefix    text        NOT NULL,
    -- SHA-256 of the token. Tokens are 256 random bits: unlike passwords they can't be guessed, so a
    -- fast hash is enough, and it allows lookup by hash on every request.
    token_hash      text        NOT NULL,
    scopes          text[]      NOT NULL,
    created_by      uuid,
    created_at      timestamptz NOT NULL,
    last_used_at    timestamptz,
    revoked_at      timestamptz,
    CONSTRAINT uq_api_credentials_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_api_credentials_kind CHECK (kind IN ('API_KEY', 'APP_INSTALLATION')),
    CONSTRAINT ck_api_credentials_scopes CHECK (cardinality(scopes) > 0),
    CONSTRAINT fk_api_credentials_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
CREATE INDEX ix_api_credentials_store ON api_credentials (store_id, created_at);

-- Hourly usage per credential, rolled up from Redis counters (tenant data, RLS).
CREATE TABLE app_usage_hourly (
    store_id        uuid        NOT NULL,
    credential_id   uuid        NOT NULL,
    hour            timestamptz NOT NULL,
    requests        integer     NOT NULL,
    client_errors   integer     NOT NULL,
    server_errors   integer     NOT NULL,
    rate_limited    integer     NOT NULL,
    CONSTRAINT pk_app_usage_hourly PRIMARY KEY (credential_id, hour),
    CONSTRAINT fk_app_usage_credential FOREIGN KEY (credential_id) REFERENCES api_credentials (id) ON DELETE CASCADE
);
CREATE INDEX ix_app_usage_store ON app_usage_hourly (store_id, hour);
ALTER TABLE app_usage_hourly ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_usage_hourly FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app_usage_hourly USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- Subscriptions created by an API credential belong to it: revoking the credential removes them.
ALTER TABLE webhook_subscriptions ADD COLUMN owner_credential_id uuid;
ALTER TABLE webhook_subscriptions ADD CONSTRAINT fk_webhook_subscriptions_owner
    FOREIGN KEY (owner_credential_id) REFERENCES api_credentials (id) ON DELETE CASCADE;
CREATE INDEX ix_webhook_subscriptions_owner ON webhook_subscriptions (owner_credential_id) WHERE owner_credential_id IS NOT NULL;

-- "Everything that changed since my last sync", in a stable order (keyset on updated_at, id).
CREATE INDEX ix_orders_store_updated ON orders (store_id, updated_at, id);
CREATE INDEX ix_inventory_items_store_updated ON inventory_items (store_id, updated_at, id);
