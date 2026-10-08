-- Phase 13c: product search in PostgreSQL. Full-text (weighted, accent-insensitive) + trigram
-- similarity for typos. Same transaction as the product write: search is never stale.

-- Trusted extensions: installable by the migration role (it has CREATE on the database).
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- 'simple' (no stemming, no stop words: product names are not prose, and stores sell in many
-- languages) + accent folding: "cafe" finds "Café".
CREATE TEXT SEARCH CONFIGURATION product_search (COPY = pg_catalog.simple);
ALTER TEXT SEARCH CONFIGURATION product_search ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple;

-- unaccent() is only STABLE (its dictionary could change); index expressions must be IMMUTABLE.
-- The usual wrapper: fix the dictionary explicitly and promise immutability.
CREATE FUNCTION search_normalize(value text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    SET search_path = storeforge, pg_temp
AS $$ SELECT lower(unaccent('unaccent'::regdictionary, value)) $$;

-- Weighted document: title (A) > vendor and type (B) > description (C). Generated and STORED, so
-- it can never drift from the row; GIN-indexed together with store_id (btree_gin): one index scan
-- answers "this tenant's products matching these words".
ALTER TABLE products ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('product_search', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('product_search', coalesce(vendor, '') || ' ' || coalesce(product_type, '')), 'B') ||
    setweight(to_tsvector('product_search', coalesce(description, '')), 'C')) STORED;
CREATE INDEX ix_products_search ON products USING gin (store_id, search_vector);

-- Typo tolerance on titles: trigram word similarity ("lampshde" ~ "Lampshade").
CREATE INDEX ix_products_title_trgm ON products USING gin (store_id, search_normalize(title) gin_trgm_ops);
