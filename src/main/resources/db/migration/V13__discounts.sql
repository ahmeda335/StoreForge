-- Phase 13a: discount codes.

CREATE TABLE discounts (
    id                      uuid        PRIMARY KEY,
    store_id                uuid        NOT NULL,
    code                    citext      NOT NULL,
    kind                    text        NOT NULL,
    -- PERCENTAGE: basis points (1050 = 10.50 %). FIXED_AMOUNT: minor units in the store currency.
    value                   bigint      NOT NULL,
    minimum_subtotal_minor  bigint      NOT NULL DEFAULT 0,
    starts_at               timestamptz NOT NULL,
    ends_at                 timestamptz,
    usage_limit             integer,
    usage_count             integer     NOT NULL DEFAULT 0,
    once_per_customer       boolean     NOT NULL DEFAULT false,
    status                  text        NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT uq_discounts_store_code UNIQUE (store_id, code),
    CONSTRAINT uq_discounts_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_discounts_kind CHECK (kind IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    CONSTRAINT ck_discounts_value CHECK (value > 0 AND (kind <> 'PERCENTAGE' OR value <= 10000)),
    CONSTRAINT ck_discounts_window CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_discounts_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    -- The "last coupon" race is decided by a conditional UPDATE; this is the backstop.
    CONSTRAINT ck_discounts_usage CHECK (usage_count >= 0 AND (usage_limit IS NULL OR usage_count <= usage_limit)),
    CONSTRAINT ck_discounts_code CHECK (code ~ '^[A-Za-z0-9_-]{3,40}$'),
    CONSTRAINT fk_discounts_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);

-- One row per use: which checkout/order used which code for how much.
CREATE TABLE discount_redemptions (
    id              uuid        PRIMARY KEY,
    store_id        uuid        NOT NULL,
    discount_id     uuid        NOT NULL,
    checkout_id     uuid        NOT NULL,
    order_id        uuid        NOT NULL,
    amount_minor    bigint      NOT NULL,
    -- Set only for once-per-customer discounts: the unique index below then enforces "once".
    customer_key    citext,
    redeemed_at     timestamptz NOT NULL,
    -- No stacking: one code per checkout.
    CONSTRAINT uq_discount_redemptions_checkout UNIQUE (store_id, checkout_id),
    CONSTRAINT uq_discount_redemptions_order UNIQUE (store_id, order_id),
    CONSTRAINT ck_discount_redemptions_amount CHECK (amount_minor > 0),
    CONSTRAINT fk_discount_redemptions_discount FOREIGN KEY (store_id, discount_id) REFERENCES discounts (store_id, id)
);
CREATE UNIQUE INDEX uq_discount_redemptions_customer ON discount_redemptions (discount_id, customer_key) WHERE customer_key IS NOT NULL;

ALTER TABLE discounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE discounts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON discounts USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE discount_redemptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE discount_redemptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON discount_redemptions USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- The code chosen in an open checkout, and the amount frozen at completion.
ALTER TABLE checkouts ADD COLUMN discount_code citext;
ALTER TABLE checkouts ADD COLUMN discount_minor bigint;

-- Orders carry the discount; the total identity now includes it.
ALTER TABLE orders ADD COLUMN discount_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN discount_code text;
ALTER TABLE orders DROP CONSTRAINT ck_orders_totals;
ALTER TABLE orders ADD CONSTRAINT ck_orders_totals CHECK (subtotal_minor >= 0 AND shipping_minor >= 0 AND tax_minor >= 0
    AND discount_minor >= 0 AND discount_minor <= subtotal_minor
    AND total_minor = subtotal_minor - discount_minor + shipping_minor + tax_minor);
