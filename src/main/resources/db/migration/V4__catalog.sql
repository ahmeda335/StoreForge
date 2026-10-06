-- Phase 4: catalog. Products are aggregates: a product with its variants is one consistency
-- boundary (variants never exist without their product; edits go through the product).
-- Both tables are tenant data: store_id + RLS + composite foreign key.

CREATE TABLE products (
    id            uuid        PRIMARY KEY,
    store_id      uuid        NOT NULL,
    handle        citext      NOT NULL,
    title         text        NOT NULL,
    description   text        NOT NULL DEFAULT '',
    vendor        text,
    product_type  text,
    status        text        NOT NULL,
    -- Up to three option names ("Size", "Color"); each variant carries one value per option.
    option_names  text[]      NOT NULL DEFAULT '{}',
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL,
    CONSTRAINT uq_products_store_handle UNIQUE (store_id, handle),
    CONSTRAINT uq_products_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_products_title_length CHECK (length(title) BETWEEN 1 AND 255),
    CONSTRAINT ck_products_handle_format CHECK (handle ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(handle) <= 100),
    CONSTRAINT ck_products_option_count CHECK (cardinality(option_names) <= 3),
    CONSTRAINT fk_products_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
-- Admin list: WHERE store_id = ? [AND status = ?] ORDER BY created_at DESC, id DESC (keyset).
CREATE INDEX ix_products_store_created ON products (store_id, created_at DESC, id DESC);
CREATE INDEX ix_products_store_status_created ON products (store_id, status, created_at DESC, id DESC);

CREATE TABLE product_variants (
    id                      uuid        PRIMARY KEY,
    store_id                uuid        NOT NULL,
    product_id              uuid        NOT NULL,
    sku                     text,
    price_minor             bigint      NOT NULL,
    compare_at_price_minor  bigint,
    option1                 text,
    option2                 text,
    option3                 text,
    position                integer     NOT NULL,
    weight_grams            integer,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT uq_product_variants_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_product_variants_price CHECK (price_minor >= 0 AND price_minor <= 100000000000),
    CONSTRAINT ck_product_variants_compare_at CHECK (compare_at_price_minor IS NULL OR compare_at_price_minor > price_minor),
    CONSTRAINT ck_product_variants_weight CHECK (weight_grams IS NULL OR weight_grams >= 0),
    CONSTRAINT ck_product_variants_sku_length CHECK (sku IS NULL OR length(sku) BETWEEN 1 AND 100),
    -- Composite FK: a variant can only belong to a product of the SAME store.
    CONSTRAINT fk_product_variants_product FOREIGN KEY (store_id, product_id)
        REFERENCES products (store_id, id) ON DELETE CASCADE
);
-- One variant per option combination. NULLS NOT DISTINCT (PG15+): two variants of a product
-- without options (all NULL) are duplicates, too.
CREATE UNIQUE INDEX uq_product_variants_options ON product_variants (product_id, option1, option2, option3) NULLS NOT DISTINCT;
-- SKUs are optional but unique within a store when present.
CREATE UNIQUE INDEX uq_product_variants_store_sku ON product_variants (store_id, sku) WHERE sku IS NOT NULL;
CREATE INDEX ix_product_variants_product ON product_variants (store_id, product_id, position);

ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON products
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

ALTER TABLE product_variants ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_variants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_variants
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());
