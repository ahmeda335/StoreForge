-- Phase 5: inventory. Stock is a SEPARATE consistency boundary from the product aggregate: it is
-- written by every purchase (hot rows, high contention) while products change rarely. See ADR-004.

CREATE TABLE inventory_items (
    id          uuid        PRIMARY KEY,
    store_id    uuid        NOT NULL,
    variant_id  uuid        NOT NULL,
    on_hand     integer     NOT NULL DEFAULT 0,
    reserved    integer     NOT NULL DEFAULT 0,
    -- Untracked items (digital goods, made-to-order) are always available and never counted.
    tracked     boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT uq_inventory_items_store_variant UNIQUE (store_id, variant_id),
    CONSTRAINT uq_inventory_items_store_id UNIQUE (store_id, id),
    -- The "no oversell" invariant, enforced by the database itself: whatever the application does,
    -- a row where more is reserved than physically exists can never be committed.
    CONSTRAINT ck_inventory_items_on_hand CHECK (on_hand >= 0),
    CONSTRAINT ck_inventory_items_reserved CHECK (reserved >= 0 AND reserved <= on_hand),
    CONSTRAINT fk_inventory_items_variant FOREIGN KEY (store_id, variant_id)
        REFERENCES product_variants (store_id, id) ON DELETE CASCADE
);

CREATE TABLE inventory_reservations (
    id                 uuid        PRIMARY KEY,
    store_id           uuid        NOT NULL,
    inventory_item_id  uuid        NOT NULL,
    checkout_id        uuid        NOT NULL,
    quantity           integer     NOT NULL,
    status             text        NOT NULL,
    expires_at         timestamptz NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    -- Reserving is idempotent per (checkout, item): a retried checkout cannot reserve twice.
    CONSTRAINT uq_inventory_reservations_checkout_item UNIQUE (store_id, checkout_id, inventory_item_id),
    CONSTRAINT ck_inventory_reservations_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inventory_reservations_status CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT fk_inventory_reservations_item FOREIGN KEY (store_id, inventory_item_id)
        REFERENCES inventory_items (store_id, id) ON DELETE CASCADE
);
-- The sweeper's access path: tiny partial index containing only live reservations.
CREATE INDEX ix_inventory_reservations_active_expiry ON inventory_reservations (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_inventory_reservations_checkout ON inventory_reservations (store_id, checkout_id);

-- Append-only ledger: every change to on_hand/reserved is a row here, so levels can be explained
-- ("why is it 3?") and reconciled (SUM(deltas) must equal the current level).
CREATE TABLE inventory_transactions (
    id                 uuid        PRIMARY KEY,
    store_id           uuid        NOT NULL,
    inventory_item_id  uuid        NOT NULL,
    delta_on_hand      integer     NOT NULL,
    delta_reserved     integer     NOT NULL,
    reason             text        NOT NULL,
    reference_type     text,
    reference_id       text,
    actor_id           uuid,
    note               text,
    created_at         timestamptz NOT NULL,
    CONSTRAINT ck_inventory_transactions_reason CHECK (reason IN
        ('RECEIVED', 'CORRECTION', 'DAMAGED', 'RETURNED', 'RESERVED', 'RELEASED', 'EXPIRED', 'SOLD')),
    CONSTRAINT fk_inventory_transactions_item FOREIGN KEY (store_id, inventory_item_id)
        REFERENCES inventory_items (store_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_inventory_transactions_item_time ON inventory_transactions (store_id, inventory_item_id, created_at DESC, id DESC);
REVOKE UPDATE, DELETE ON inventory_transactions FROM ${app_role};

ALTER TABLE inventory_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_items
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

ALTER TABLE inventory_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_reservations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_reservations
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

ALTER TABLE inventory_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_transactions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_transactions
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- ---------------------------------------------------------------- cross-tenant scan (ADR-011)
-- The expiry sweeper must find WHICH stores have expired reservations, a question that spans
-- tenants and is therefore invisible under RLS. This function answers only that: store ids, no
-- data. The actual expiry then runs per store inside a normal tenant transaction (RLS applies).
--   SECURITY DEFINER: runs with the privileges of its OWNER (the BYPASSRLS scanner role).
--   SET search_path: a definer function must pin its search_path, or a caller could create a
--     same-named table/function earlier in the path and hijack the elevated execution.
CREATE FUNCTION stores_with_expired_reservations(max_stores integer)
    RETURNS TABLE (store_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = storeforge, pg_temp
AS $$
    SELECT DISTINCT r.store_id
      FROM inventory_reservations r
     WHERE r.status = 'ACTIVE' AND r.expires_at < now()
     LIMIT max_stores
$$;
GRANT SELECT ON inventory_reservations TO storeforge_tenant_scanner;
ALTER FUNCTION stores_with_expired_reservations(integer) OWNER TO storeforge_tenant_scanner;
REVOKE ALL ON FUNCTION stores_with_expired_reservations(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION stores_with_expired_reservations(integer) TO ${app_role};
