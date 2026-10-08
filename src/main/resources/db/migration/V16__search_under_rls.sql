-- Phase 13c fix (EXP-018): text search indexes are unusable under row-level security.
--
-- RLS must evaluate the policy before any user condition that could leak data. Operators that are
-- not LEAKPROOF are therefore never used as index conditions on an RLS table: '@@' (ts_match_vq)
-- and '%>' (trigram) are not leakproof, so with RLS the GIN indexes served only their store_id
-- column and every product of the store was filtered row by row (seconds at 200k products).
--
-- The match step runs in a narrow SECURITY DEFINER function (ADR-011 pattern), owned by the
-- RLS-bypassing scanner role. It takes NO store argument: the store is the transaction's tenant
-- (current_store_id(), the same setting the RLS policies use), so a caller can't aim it at another
-- store, and outside a tenant transaction it matches nothing. It returns ONLY ids and scores;
-- product data is then read by the caller under RLS as usual.
CREATE FUNCTION search_product_matches(p_query text)
    RETURNS TABLE (product_id uuid, rank real, similarity real)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = storeforge, pg_temp
AS $$
    WITH s AS (SELECT current_store_id() AS id),
    q AS (SELECT websearch_to_tsquery('product_search', p_query) AS tsq, search_normalize(p_query) AS norm),
    hits AS (   -- UNION of two index scans; an OR of two GIN conditions would not use both indexes
        SELECT p.id FROM products p, q, s WHERE p.store_id = s.id AND p.status = 'ACTIVE' AND p.search_vector @@ q.tsq
        UNION
        SELECT p.id FROM products p, q, s WHERE p.store_id = s.id AND p.status = 'ACTIVE' AND search_normalize(p.title) %> q.norm
    )
    SELECT p.id, ts_rank_cd(p.search_vector, q.tsq), word_similarity(q.norm, search_normalize(p.title))
      FROM hits h JOIN products p ON p.id = h.id CROSS JOIN q CROSS JOIN s
     WHERE p.store_id = s.id
$$;
GRANT SELECT ON products TO storeforge_tenant_scanner;
ALTER FUNCTION search_product_matches(text) OWNER TO storeforge_tenant_scanner;
REVOKE ALL ON FUNCTION search_product_matches(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION search_product_matches(text) TO ${app_role};
