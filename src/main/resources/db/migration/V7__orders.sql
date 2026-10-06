-- Phase 8: orders. An order is created inside the checkout-completion transaction and owns the
-- lifecycle from there (payment, fulfillment, cancellation). Lines are immutable snapshots.

-- Per-store order numbers (#1001, #1002, ...). A counter ROW, not a sequence: PostgreSQL sequences
-- are not per tenant, and they are not transactional (a rolled-back order would leave a gap).
-- The row lock taken by the UPDATE serializes order numbering per store, for milliseconds.
CREATE TABLE store_counters (
    store_id           uuid    PRIMARY KEY,
    next_order_number  bigint  NOT NULL,
    CONSTRAINT fk_store_counters_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);

CREATE TABLE orders (
    id                  uuid        PRIMARY KEY,
    store_id            uuid        NOT NULL,
    number              bigint      NOT NULL,
    checkout_id         uuid        NOT NULL,
    customer_id         uuid,
    email               citext      NOT NULL,
    currency            char(3)     NOT NULL,
    subtotal_minor      bigint      NOT NULL,
    shipping_minor      bigint      NOT NULL,
    tax_minor           bigint      NOT NULL,
    total_minor         bigint      NOT NULL,
    status              text        NOT NULL,
    financial_status    text        NOT NULL,
    fulfillment_status  text        NOT NULL,
    ship_name           text        NOT NULL,
    ship_line1          text        NOT NULL,
    ship_line2          text,
    ship_city           text        NOT NULL,
    ship_region         text,
    ship_postal_code    text        NOT NULL,
    ship_country        char(2)     NOT NULL,
    payment_due_at      timestamptz NOT NULL,
    paid_at             timestamptz,
    fulfilled_at        timestamptz,
    cancelled_at        timestamptz,
    cancel_reason       text,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL,
    CONSTRAINT uq_orders_store_number UNIQUE (store_id, number),
    -- One order per checkout, whatever happens in application code.
    CONSTRAINT uq_orders_store_checkout UNIQUE (store_id, checkout_id),
    CONSTRAINT uq_orders_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_orders_status CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_orders_financial_status CHECK (financial_status IN ('PENDING', 'PAID', 'VOIDED')),
    CONSTRAINT ck_orders_fulfillment_status CHECK (fulfillment_status IN ('UNFULFILLED', 'FULFILLED')),
    CONSTRAINT ck_orders_totals CHECK (subtotal_minor >= 0 AND shipping_minor >= 0 AND tax_minor >= 0
        AND total_minor = subtotal_minor + shipping_minor + tax_minor),
    -- State combinations the state machine must never produce, enforced again by the database.
    CONSTRAINT ck_orders_cancelled_unpaid CHECK (status <> 'CANCELLED' OR (financial_status = 'VOIDED' AND fulfillment_status = 'UNFULFILLED')),
    CONSTRAINT ck_orders_fulfilled_paid CHECK (fulfillment_status <> 'FULFILLED' OR financial_status = 'PAID'),
    CONSTRAINT fk_orders_checkout FOREIGN KEY (store_id, checkout_id) REFERENCES checkouts (store_id, id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (store_id, customer_id) REFERENCES customers (store_id, id)
);
CREATE INDEX ix_orders_store_created ON orders (store_id, created_at DESC, id DESC);
CREATE INDEX ix_orders_store_customer ON orders (store_id, customer_id);
-- The expiry job's access path: only unpaid open orders.
CREATE INDEX ix_orders_payment_due ON orders (payment_due_at) WHERE status = 'OPEN' AND financial_status = 'PENDING';

CREATE TABLE order_items (
    store_id          uuid    NOT NULL,
    order_id          uuid    NOT NULL,
    variant_id        uuid    NOT NULL,
    product_title     text    NOT NULL,
    variant_title     text    NOT NULL,
    sku               text,
    unit_price_minor  bigint  NOT NULL,
    quantity          integer NOT NULL,
    line_total_minor  bigint  NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (order_id, variant_id),
    CONSTRAINT ck_order_items_amounts CHECK (unit_price_minor >= 0 AND quantity > 0 AND line_total_minor = unit_price_minor * quantity),
    CONSTRAINT fk_order_items_order FOREIGN KEY (store_id, order_id) REFERENCES orders (store_id, id)
);
-- What was sold at which price is history: the application can add lines, never change them.
REVOKE UPDATE, DELETE ON order_items FROM ${app_role};

-- Customer-service timeline of an order (append-only).
CREATE TABLE order_events (
    id          uuid        PRIMARY KEY,
    store_id    uuid        NOT NULL,
    order_id    uuid        NOT NULL,
    type        text        NOT NULL,
    actor_id    uuid,
    details     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL,
    CONSTRAINT ck_order_events_type CHECK (type IN ('PLACED', 'PAID', 'FULFILLED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT fk_order_events_order FOREIGN KEY (store_id, order_id) REFERENCES orders (store_id, id)
);
CREATE INDEX ix_order_events_order ON order_events (store_id, order_id, created_at, id);
REVOKE UPDATE, DELETE ON order_events FROM ${app_role};

ALTER TABLE checkouts ADD COLUMN order_id uuid;
ALTER TABLE checkouts ADD CONSTRAINT fk_checkouts_order FOREIGN KEY (store_id, order_id) REFERENCES orders (store_id, id);

ALTER TABLE store_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE store_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON store_counters USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON orders USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_items USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE order_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_events USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- Cross-tenant discovery for the unpaid-order expiry job (ADR-011 pattern): store ids only.
-- The cutoff is a parameter (not now()) so the job and its tests control time explicitly.
CREATE FUNCTION stores_with_overdue_orders(cutoff timestamptz, max_stores integer)
    RETURNS TABLE (store_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = storeforge, pg_temp
AS $$
    SELECT DISTINCT o.store_id
      FROM orders o
     WHERE o.status = 'OPEN' AND o.financial_status = 'PENDING' AND o.payment_due_at < cutoff
     LIMIT max_stores
$$;
GRANT SELECT ON orders TO storeforge_tenant_scanner;
ALTER FUNCTION stores_with_overdue_orders(timestamptz, integer) OWNER TO storeforge_tenant_scanner;
REVOKE ALL ON FUNCTION stores_with_overdue_orders(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION stores_with_overdue_orders(timestamptz, integer) TO ${app_role};
