-- P15 (EXP-023): search computed search_normalize(title) = lower(unaccent(title)) for every candidate
-- row, twice (trigram recheck + similarity): ~9 million calls per 2-minute load run. Normalize once,
-- on write, into a stored generated column, and index and read that instead.
ALTER TABLE products ADD COLUMN title_search text GENERATED ALWAYS AS (search_normalize(title)) STORED;
CREATE INDEX ix_products_title_search_trgm ON products USING gin (store_id, title_search gin_trgm_ops);
DROP INDEX ix_products_title_trgm;

CREATE OR REPLACE FUNCTION search_product_matches(p_query text)
    RETURNS TABLE (product_id uuid, rank real, similarity real)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = storeforge, pg_temp
AS $$
    WITH s AS (SELECT current_store_id() AS id),
    q AS (SELECT websearch_to_tsquery('product_search', p_query) AS tsq, search_normalize(p_query) AS norm),
    hits AS (   -- UNION of two index scans; an OR of two GIN conditions would not use both indexes
        SELECT p.id FROM products p, q, s WHERE p.store_id = s.id AND p.status = 'ACTIVE' AND p.search_vector @@ q.tsq
        UNION
        SELECT p.id FROM products p, q, s WHERE p.store_id = s.id AND p.status = 'ACTIVE' AND p.title_search %> q.norm
    )
    SELECT p.id, ts_rank_cd(p.search_vector, q.tsq), word_similarity(q.norm, p.title_search)
      FROM hits h JOIN products p ON p.id = h.id CROSS JOIN q CROSS JOIN s
     WHERE p.store_id = s.id
$$;
