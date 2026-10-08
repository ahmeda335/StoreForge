-- Phase 13e: product images in object storage. The row is the source of truth; the object is
-- referenced by a server-chosen, tenant-prefixed key: stores/{store}/products/{product}/{image}.
--   PENDING: an upload URL was issued; the client may or may not have uploaded.
--   READY:   the application verified the stored object (size and file signature).
-- Abandoned PENDING rows are swept, and their objects deleted (by a retried job).
CREATE TABLE product_images (
    id            uuid        PRIMARY KEY,
    store_id      uuid        NOT NULL,
    product_id    uuid        NOT NULL,
    object_key    text        NOT NULL,
    content_type  text        NOT NULL,
    size_bytes    bigint      NOT NULL,
    alt           text,
    position      integer,
    status        text        NOT NULL,
    created_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    CONSTRAINT uq_product_images_key UNIQUE (object_key),
    CONSTRAINT ck_product_images_key_prefix CHECK (object_key = 'stores/' || store_id || '/products/' || product_id || '/' || id),
    CONSTRAINT ck_product_images_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_product_images_size CHECK (size_bytes BETWEEN 1 AND 20971520),
    CONSTRAINT ck_product_images_alt CHECK (alt IS NULL OR length(alt) <= 500),
    CONSTRAINT ck_product_images_status CHECK (status IN ('PENDING', 'READY')),
    CONSTRAINT ck_product_images_ready CHECK ((status = 'READY') = (position IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT fk_product_images_product FOREIGN KEY (store_id, product_id) REFERENCES products (store_id, id)
);
CREATE INDEX ix_product_images_product ON product_images (store_id, product_id, position);
CREATE INDEX ix_product_images_pending ON product_images (created_at) WHERE status = 'PENDING';

ALTER TABLE product_images ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_images FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_images USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- Cross-tenant scan (ADR-011): which stores have abandoned uploads? Store ids only; the cleanup
-- itself runs per store under RLS.
CREATE FUNCTION stores_with_abandoned_image_uploads(created_before timestamptz, max_stores integer)
    RETURNS TABLE (store_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = storeforge, pg_temp
AS $$
    SELECT DISTINCT i.store_id FROM product_images i
     WHERE i.status = 'PENDING' AND i.created_at < created_before
     LIMIT max_stores
$$;
GRANT SELECT ON product_images TO storeforge_tenant_scanner;
ALTER FUNCTION stores_with_abandoned_image_uploads(timestamptz, integer) OWNER TO storeforge_tenant_scanner;
REVOKE ALL ON FUNCTION stores_with_abandoned_image_uploads(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION stores_with_abandoned_image_uploads(timestamptz, integer) TO ${app_role};
