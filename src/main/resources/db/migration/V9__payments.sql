-- Phase 10: payments, refunds, provider webhooks, refunds on orders.

-- Orders learn about refunds.
ALTER TABLE orders DROP CONSTRAINT ck_orders_financial_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_financial_status
    CHECK (financial_status IN ('PENDING', 'PAID', 'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED'));
ALTER TABLE orders DROP CONSTRAINT ck_orders_fulfilled_paid;
ALTER TABLE orders ADD CONSTRAINT ck_orders_fulfilled_paid
    CHECK (fulfillment_status <> 'FULFILLED' OR financial_status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED'));
-- A paid order can be cancelled once its money went back (fully refunded).
ALTER TABLE orders DROP CONSTRAINT ck_orders_cancelled_unpaid;
ALTER TABLE orders ADD CONSTRAINT ck_orders_cancelled_unpaid
    CHECK (status <> 'CANCELLED' OR (financial_status IN ('VOIDED', 'REFUNDED') AND fulfillment_status = 'UNFULFILLED'));
ALTER TABLE orders ADD COLUMN refunded_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE orders ADD CONSTRAINT ck_orders_refunded CHECK (refunded_minor >= 0 AND refunded_minor <= total_minor);
ALTER TABLE order_events DROP CONSTRAINT ck_order_events_type;
ALTER TABLE order_events ADD CONSTRAINT ck_order_events_type
    CHECK (type IN ('PLACED', 'PAID', 'FULFILLED', 'CANCELLED', 'EXPIRED', 'REFUNDED', 'PAYMENT_FAILED'));

-- One payment per order. Card data never touches StoreForge: the browser sends it to the provider.
CREATE TABLE payments (
    id                   uuid        PRIMARY KEY,
    store_id             uuid        NOT NULL,
    order_id             uuid        NOT NULL,
    provider             text        NOT NULL,
    provider_payment_id  text,
    client_secret        text,
    amount_minor         bigint      NOT NULL,
    currency             char(3)     NOT NULL,
    status               text        NOT NULL,
    captured_minor       bigint      NOT NULL DEFAULT 0,
    refunded_minor       bigint      NOT NULL DEFAULT 0,
    failure_code         text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL,
    CONSTRAINT uq_payments_store_order UNIQUE (store_id, order_id),
    CONSTRAINT uq_payments_store_id UNIQUE (store_id, id),
    CONSTRAINT uq_payments_provider_ref UNIQUE (provider, provider_payment_id),
    CONSTRAINT ck_payments_status CHECK (status IN ('CREATED', 'PENDING', 'CAPTURED', 'FAILED', 'CANCELLED',
                                                    'PARTIALLY_REFUNDED', 'REFUNDED')),
    -- The money invariants, enforced by the database: never refund more than was captured,
    -- never capture more than was asked for.
    CONSTRAINT ck_payments_amounts CHECK (amount_minor > 0 AND captured_minor >= 0 AND captured_minor <= amount_minor
                                          AND refunded_minor >= 0 AND refunded_minor <= captured_minor),
    CONSTRAINT fk_payments_order FOREIGN KEY (store_id, order_id) REFERENCES orders (store_id, id)
);
CREATE INDEX ix_payments_stale ON payments (updated_at) WHERE status IN ('CREATED', 'PENDING');

-- Every call to the provider, recorded BEFORE it is made (status IN_PROGRESS, committed), finished
-- afterwards. A crash or timeout in between leaves an IN_PROGRESS/UNKNOWN row that is retried with
-- the SAME idempotency key, so the provider performs the operation at most once.
CREATE TABLE payment_operations (
    id                  uuid        PRIMARY KEY,
    store_id            uuid        NOT NULL,
    payment_id          uuid        NOT NULL,
    kind                text        NOT NULL,
    idempotency_key     text        NOT NULL,
    status              text        NOT NULL,
    attempts            integer     NOT NULL DEFAULT 1,
    amount_minor        bigint,
    provider_reference  text,
    error_code          text,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT uq_payment_operations_key UNIQUE (store_id, idempotency_key),
    CONSTRAINT ck_payment_operations_kind CHECK (kind IN ('CREATE', 'REFUND', 'CANCEL')),
    CONSTRAINT ck_payment_operations_status CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT fk_payment_operations_payment FOREIGN KEY (store_id, payment_id) REFERENCES payments (store_id, id)
);

CREATE TABLE refunds (
    id                  uuid        PRIMARY KEY,
    store_id            uuid        NOT NULL,
    payment_id          uuid        NOT NULL,
    amount_minor        bigint      NOT NULL,
    reason              text        NOT NULL,
    status              text        NOT NULL,
    provider_refund_id  text,
    idempotency_key     text        NOT NULL,
    created_by          uuid,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT uq_refunds_key UNIQUE (store_id, idempotency_key),
    CONSTRAINT ck_refunds_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (store_id, payment_id) REFERENCES payments (store_id, id)
);
CREATE UNIQUE INDEX uq_refunds_provider_ref ON refunds (provider_refund_id) WHERE provider_refund_id IS NOT NULL;

ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE payment_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_operations USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE refunds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refunds USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- Inbound provider webhooks (INFRASTRUCTURE, like jobs: arrives before any tenant context).
-- The primary key IS the deduplication: providers retry deliveries and may send duplicates.
CREATE TABLE provider_events (
    provider     text        NOT NULL,
    event_id     text        NOT NULL,
    type         text        NOT NULL,
    store_id     uuid,
    payload      jsonb       NOT NULL,
    received_at  timestamptz NOT NULL,
    CONSTRAINT pk_provider_events PRIMARY KEY (provider, event_id)
);

CREATE INDEX ix_refunds_pending ON refunds (updated_at) WHERE status = 'PENDING';

-- Reconciliation (ADR-011 pattern): which stores have payments or refunds stuck before a final state?
CREATE FUNCTION stores_with_stale_payments(cutoff timestamptz, max_stores integer)
    RETURNS TABLE (store_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = storeforge, pg_temp
AS $$
    SELECT s.store_id FROM (
        SELECT p.store_id FROM payments p WHERE p.status IN ('CREATED', 'PENDING') AND p.updated_at < cutoff
        UNION
        SELECT r.store_id FROM refunds r WHERE r.status = 'PENDING' AND r.updated_at < cutoff
    ) s
    LIMIT max_stores
$$;
GRANT SELECT ON payments, refunds TO storeforge_tenant_scanner;
ALTER FUNCTION stores_with_stale_payments(timestamptz, integer) OWNER TO storeforge_tenant_scanner;
REVOKE ALL ON FUNCTION stores_with_stale_payments(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION stores_with_stale_payments(timestamptz, integer) TO ${app_role};
