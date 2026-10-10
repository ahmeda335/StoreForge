-- P15 (EXP-023): the storefront's product-by-handle lookup scanned every product of every store.
-- Under RLS an index condition must use LEAKPROOF operators; citext's "=" (citext_eq) is not, so the
-- unique (store_id, handle) index can never serve "handle = …" for the application role.
-- Handles are lowercase by constraint (ck_products_handle_format), so comparing the TEXT value with
-- a lowercased parameter is exactly as case-insensitive, and text "=" (texteq) is leakproof.
CREATE INDEX ix_products_store_handle_text ON products (store_id, (handle::text));
