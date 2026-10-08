-- Phase 13b: shipping rates (chosen at checkout) and shipments (partial fulfilment).

CREATE TABLE shipping_rates (
    id                  uuid        PRIMARY KEY,
    store_id            uuid        NOT NULL,
    name                text        NOT NULL,
    -- Destination countries (ISO 3166-1 alpha-2); empty = every country.
    countries           text[]      NOT NULL DEFAULT '{}',
    -- Weight band [min, max): a flat rate is 0..NULL; weight-based rates are several bands.
    min_weight_grams    integer     NOT NULL DEFAULT 0,
    max_weight_grams    integer,
    price_minor         bigint      NOT NULL,
    -- Free when the (discounted) subtotal reaches this amount.
    free_over_minor     bigint,
    active              boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT uq_shipping_rates_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_shipping_rates_price CHECK (price_minor >= 0),
    CONSTRAINT ck_shipping_rates_weight CHECK (min_weight_grams >= 0 AND (max_weight_grams IS NULL OR max_weight_grams > min_weight_grams)),
    CONSTRAINT ck_shipping_rates_free CHECK (free_over_minor IS NULL OR free_over_minor > 0),
    CONSTRAINT fk_shipping_rates_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
ALTER TABLE shipping_rates ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipping_rates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shipping_rates USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

ALTER TABLE checkouts ADD COLUMN shipping_rate_id uuid;
ALTER TABLE orders ADD COLUMN shipping_rate_name text;

-- Partial fulfilment.
ALTER TABLE orders DROP CONSTRAINT ck_orders_fulfillment_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_fulfillment_status CHECK (fulfillment_status IN ('UNFULFILLED', 'PARTIALLY_FULFILLED', 'FULFILLED'));
ALTER TABLE orders DROP CONSTRAINT ck_orders_fulfilled_paid;
ALTER TABLE orders ADD CONSTRAINT ck_orders_fulfilled_paid
    CHECK (fulfillment_status = 'UNFULFILLED' OR financial_status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED'));
ALTER TABLE order_events DROP CONSTRAINT ck_order_events_type;
ALTER TABLE order_events ADD CONSTRAINT ck_order_events_type
    CHECK (type IN ('PLACED', 'PAID', 'FULFILLED', 'CANCELLED', 'EXPIRED', 'REFUNDED', 'PAYMENT_FAILED',
                    'SHIPPED', 'DELIVERED', 'SHIPMENT_CANCELLED'));

CREATE TABLE shipments (
    id               uuid        PRIMARY KEY,
    store_id         uuid        NOT NULL,
    order_id         uuid        NOT NULL,
    status           text        NOT NULL,
    carrier          text,
    tracking_number  text,
    created_by       uuid,
    shipped_at       timestamptz NOT NULL,
    delivered_at     timestamptz,
    cancelled_at     timestamptz,
    CONSTRAINT uq_shipments_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_shipments_status CHECK (status IN ('SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT fk_shipments_order FOREIGN KEY (store_id, order_id) REFERENCES orders (store_id, id)
);
CREATE INDEX ix_shipments_order ON shipments (store_id, order_id);

CREATE TABLE shipment_items (
    store_id     uuid    NOT NULL,
    shipment_id  uuid    NOT NULL,
    variant_id   uuid    NOT NULL,
    quantity     integer NOT NULL,
    CONSTRAINT pk_shipment_items PRIMARY KEY (shipment_id, variant_id),
    CONSTRAINT ck_shipment_items_quantity CHECK (quantity > 0),
    CONSTRAINT fk_shipment_items_shipment FOREIGN KEY (store_id, shipment_id) REFERENCES shipments (store_id, id)
);
ALTER TABLE shipments ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shipments USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE shipment_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shipment_items USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
