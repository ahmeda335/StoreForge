-- Phase 11: outbound webhooks. Subscriptions, one delivery per (subscription, event), attempt history.
-- All tenant data (RLS): deliveries run inside tenant transactions.

CREATE TABLE webhook_subscriptions (
    id                          uuid        PRIMARY KEY,
    store_id                    uuid        NOT NULL,
    url                         text        NOT NULL,
    topics                      text[]      NOT NULL,
    api_version                 text        NOT NULL,
    status                      text        NOT NULL,
    -- We must SIGN with the secret, so it can't be hashed: AES-GCM ciphertext, key outside the database.
    secret_ciphertext           text        NOT NULL,
    previous_secret_ciphertext  text,
    previous_secret_expires_at  timestamptz,
    consecutive_failures        integer     NOT NULL DEFAULT 0,
    last_success_at             timestamptz,
    last_failure_at             timestamptz,
    paused_at                   timestamptz,
    created_by                  uuid,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    CONSTRAINT uq_webhook_subscriptions_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_webhook_subscriptions_status CHECK (status IN ('ACTIVE', 'PAUSED_FAILING', 'DISABLED')),
    CONSTRAINT ck_webhook_subscriptions_topics CHECK (cardinality(topics) > 0),
    CONSTRAINT ck_webhook_subscriptions_previous CHECK ((previous_secret_ciphertext IS NULL) = (previous_secret_expires_at IS NULL)),
    CONSTRAINT fk_webhook_subscriptions_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
CREATE INDEX ix_webhook_subscriptions_store ON webhook_subscriptions (store_id);

CREATE TABLE webhook_deliveries (
    id               uuid        PRIMARY KEY,
    store_id         uuid        NOT NULL,
    subscription_id  uuid        NOT NULL,
    event_id         uuid        NOT NULL,
    topic            text        NOT NULL,
    -- The exact JSON body, frozen at fan-out: every attempt and replay sends the same bytes.
    payload          text        NOT NULL,
    status           text        NOT NULL,
    attempts         integer     NOT NULL DEFAULT 0,   -- in the current cycle (a replay starts a new one)
    replays          integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    last_status_code integer,
    last_error       text,
    delivered_at     timestamptz,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    -- Idempotent fan-out: a redelivered internal event can't create a second delivery.
    CONSTRAINT uq_webhook_deliveries_subscription_event UNIQUE (subscription_id, event_id),
    CONSTRAINT uq_webhook_deliveries_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_webhook_deliveries_status CHECK (status IN ('PENDING', 'RETRYING', 'SUCCEEDED', 'DEAD', 'PAUSED', 'DISCARDED')),
    CONSTRAINT fk_webhook_deliveries_subscription FOREIGN KEY (store_id, subscription_id)
        REFERENCES webhook_subscriptions (store_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_webhook_deliveries_history ON webhook_deliveries (store_id, subscription_id, created_at DESC, id DESC);
CREATE INDEX ix_webhook_deliveries_created ON webhook_deliveries (created_at);

-- What we sent and what came back. Never request headers (they carry signatures).
CREATE TABLE webhook_delivery_attempts (
    id                uuid        PRIMARY KEY,
    store_id          uuid        NOT NULL,
    delivery_id       uuid        NOT NULL,
    attempt_number    integer     NOT NULL,
    requested_at      timestamptz NOT NULL,
    duration_ms       integer     NOT NULL,
    status_code       integer,
    outcome           text        NOT NULL,
    error             text,
    response_excerpt  text,
    CONSTRAINT uq_webhook_attempts_number UNIQUE (delivery_id, attempt_number),
    CONSTRAINT ck_webhook_attempts_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT fk_webhook_attempts_delivery FOREIGN KEY (store_id, delivery_id)
        REFERENCES webhook_deliveries (store_id, id) ON DELETE CASCADE
);

ALTER TABLE webhook_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_subscriptions USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE webhook_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_deliveries USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE webhook_delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_delivery_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_delivery_attempts USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- Retention across tenants: delete-only, returns a count, finished deliveries only (ADR-016 pattern).
CREATE FUNCTION purge_webhook_deliveries(cutoff timestamptz)
    RETURNS integer
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = storeforge, pg_temp
AS $$
    WITH deleted AS (
        DELETE FROM webhook_deliveries
         WHERE created_at < cutoff AND status IN ('SUCCEEDED', 'DEAD', 'DISCARDED')
        RETURNING 1
    )
    SELECT count(*)::integer FROM deleted
$$;
GRANT SELECT, DELETE ON webhook_deliveries TO storeforge_tenant_scanner;
ALTER FUNCTION purge_webhook_deliveries(timestamptz) OWNER TO storeforge_tenant_scanner;
REVOKE ALL ON FUNCTION purge_webhook_deliveries(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_webhook_deliveries(timestamptz) TO ${app_role};
