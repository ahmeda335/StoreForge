# ADR-025: Product search stays in PostgreSQL (full text + trigram), matched by a tenant-bound definer function; no cache yet

- **Status:** Accepted
- **Date:** 2026-10-08
- **Phase:** P13
- **Evidence:** [EXP-018](../experiments/EXP-018-search-at-scale-under-rls.md); `ProductSearchIT`, `RowLevelSecurityIT`

## Context
The storefront needs search: words in any order, accent-insensitive, tolerant of typos, with filters (vendor, type, price), sorting and facet counts. Catalogs range from a few dozen products to, in our stress case, 200,000.

## Problem
1. Search engine or database?
2. How do search indexes work on tables under row-level security?
3. Is a cache needed (blueprint 13: "only if measured")?

## Options Considered
1. **Engine:** Elasticsearch/OpenSearch (separate cluster, sync pipeline, eventual consistency); **PostgreSQL full text (`tsvector`, GIN) + `pg_trgm`**.
2. **Under RLS:** accept the slow plans; disable RLS on products; **a narrow SECURITY DEFINER function for the match step only**.
3. **Caching:** cache search responses in Redis; cache facets per store; **no cache**.

## Decision
1. **PostgreSQL.**
   - A generated, stored, weighted `search_vector` (title A, vendor/type B, description C) using a `simple` + `unaccent` text search configuration (no stemming: product names aren't prose, and stores sell in many languages).
   - GIN on `(store_id, search_vector)` (btree_gin) and trigram GIN on the normalized title.
   - `websearch_to_tsquery` syntax OR trigram word similarity; ranked by `ts_rank_cd`, then similarity, then newest.
   - Disjunctive facets; capped offset paging (relevance isn't a keyset).
   - One statement returns page, total and facets.
2. **`search_product_matches(query)`**, SECURITY DEFINER, owned by the scanner role: returns only ids and scores, scopes itself by `current_store_id()` (no store argument, so it can't be aimed at another tenant; outside a tenant transaction it matches nothing). Product data is then read under RLS.
3. **No cache.**

## Why We Chose It
- **No second system** to run, sync and reconcile; search is consistent in the same transaction as the product write (tested).
- **RLS:** PostgreSQL never uses a non-LEAKPROOF operator (`@@`, `%>`) as an index condition on an RLS table, because the policy must run first. Measured: 2–15 s per search at 200k products, timeouts under load. With the definer function: 44–340 ms for one client.
- **No cache, by measurement:** at 5,000 products every query shape has p95 ≤ 81 ms with 8 concurrent clients. Only browsing a 200k-product catalog without words stays above 300 ms. A cache would add invalidation and stampede problems to fix a case no store has yet.

## Trade-offs
- PostgreSQL search has no synonyms, per-language stemming, learning-to-rank or typo tolerance beyond the title.
- The definer function is a privileged surface: it returns ids and scores only, and `RowLevelSecurityIT` checks its tenant scoping (mutation-checked).
- Offset paging is capped at 1,000.

## Consequences
- **Revisit** when a store passes ~50k products or needs synonyms/languages: first a per-store facet cache keyed by a catalog version, then an engine.
- Every future search-like query on an RLS table must check its plan **as the application role** (EXP-018 found the problem only that way).
