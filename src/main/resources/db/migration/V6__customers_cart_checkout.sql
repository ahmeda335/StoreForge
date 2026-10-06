-- Phase 7: customers (store-scoped), carts, checkouts, and stored idempotency results.
-- All tenant data: store_id + RLS + composite foreign keys.

CREATE TABLE customers (
    id          uuid        PRIMARY KEY,
    store_id    uuid        NOT NULL,
    -- The same person in two stores is two customers: stores never share customer data.
    email       citext      NOT NULL,
    first_name  text,
    last_name   text,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT uq_customers_store_email UNIQUE (store_id, email),
    CONSTRAINT uq_customers_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_customers_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
CREATE INDEX ix_customers_store_created ON customers (store_id, created_at DESC, id DESC);

CREATE TABLE carts (
    id          uuid        PRIMARY KEY,
    store_id    uuid        NOT NULL,
    -- The cart token is a bearer secret (whoever holds it controls the cart): stored hashed only.
    token_hash  text        NOT NULL,
    status      text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT uq_carts_token_hash UNIQUE (token_hash),
    CONSTRAINT uq_carts_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_carts_status CHECK (status IN ('OPEN', 'CONVERTED', 'ABANDONED')),
    CONSTRAINT fk_carts_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);

CREATE TABLE cart_items (
    store_id    uuid        NOT NULL,
    cart_id     uuid        NOT NULL,
    variant_id  uuid        NOT NULL,
    quantity    integer     NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    -- Adding the same variant twice increments the quantity instead of creating a second line.
    CONSTRAINT pk_cart_items PRIMARY KEY (cart_id, variant_id),
    CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 100),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (store_id, cart_id) REFERENCES carts (store_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_variant FOREIGN KEY (store_id, variant_id) REFERENCES product_variants (store_id, id) ON DELETE CASCADE
);

CREATE TABLE checkouts (
    id                      uuid        PRIMARY KEY,
    store_id                uuid        NOT NULL,
    cart_id                 uuid        NOT NULL,
    status                  text        NOT NULL,
    email                   citext,
    ship_name               text,
    ship_line1              text,
    ship_line2              text,
    ship_city               text,
    ship_region             text,
    ship_postal_code        text,
    ship_country            char(2),
    customer_id             uuid,
    currency                char(3)     NOT NULL,
    -- Totals are frozen at completion (NULL while OPEN: an open checkout is always priced live).
    subtotal_minor          bigint,
    shipping_minor          bigint,
    tax_minor               bigint,
    total_minor             bigint,
    completed_at            timestamptz,
    reservation_expires_at  timestamptz,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT uq_checkouts_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_checkouts_status CHECK (status IN ('OPEN', 'COMPLETED', 'EXPIRED')),
    CONSTRAINT ck_checkouts_completed_totals CHECK (status <> 'COMPLETED' OR (total_minor IS NOT NULL AND total_minor >= 0)),
    CONSTRAINT fk_checkouts_cart FOREIGN KEY (store_id, cart_id) REFERENCES carts (store_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_checkouts_customer FOREIGN KEY (store_id, customer_id) REFERENCES customers (store_id, id)
);
-- One OPEN checkout per cart.
CREATE UNIQUE INDEX uq_checkouts_open_per_cart ON checkouts (store_id, cart_id) WHERE status = 'OPEN';

-- Snapshot of what was bought at which price. Never recomputed from the catalog afterwards.
CREATE TABLE checkout_lines (
    store_id          uuid    NOT NULL,
    checkout_id       uuid    NOT NULL,
    variant_id        uuid    NOT NULL,
    product_title     text    NOT NULL,
    variant_title     text    NOT NULL,
    sku               text,
    unit_price_minor  bigint  NOT NULL,
    quantity          integer NOT NULL,
    line_total_minor  bigint  NOT NULL,
    CONSTRAINT pk_checkout_lines PRIMARY KEY (checkout_id, variant_id),
    CONSTRAINT ck_checkout_lines_amounts CHECK (unit_price_minor >= 0 AND quantity > 0 AND line_total_minor = unit_price_minor * quantity),
    CONSTRAINT fk_checkout_lines_checkout FOREIGN KEY (store_id, checkout_id) REFERENCES checkouts (store_id, id) ON DELETE CASCADE
);

-- Stored outcomes of idempotent requests: same key → same response, the work runs at most once.
CREATE TABLE idempotency_keys (
    store_id         uuid        NOT NULL,
    scope            text        NOT NULL,
    idempotency_key  text        NOT NULL,
    request_hash     text        NOT NULL,
    response_status  integer     NOT NULL,
    response_body    text        NOT NULL,
    created_at       timestamptz NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (store_id, scope, idempotency_key),
    CONSTRAINT ck_idempotency_keys_key_length CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT fk_idempotency_keys_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
CREATE INDEX ix_idempotency_keys_created ON idempotency_keys (created_at);

ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customers USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE carts ENABLE ROW LEVEL SECURITY;
ALTER TABLE carts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON carts USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE cart_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE cart_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cart_items USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE checkouts ENABLE ROW LEVEL SECURITY;
ALTER TABLE checkouts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checkouts USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE checkout_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE checkout_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checkout_lines USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_keys USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
