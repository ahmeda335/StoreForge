# EXP-018: Search at 200,000 products, under row-level security

- **Date:** 2026-10-08 · **Phase:** P13
- **Setup:**
  - the app from the jar against Compose PostgreSQL 17 (laptop, 12 cores);
  - one measured store with 200,000 products (10% drafts, generated titles/vendors/types/descriptions, one variant each) plus 100,000 products in 50 other stores ([seed](data/exp-018-seed.sql));
  - a 5,000-product store for the realistic case;
  - [load script](data/exp-018-load.py): 8 query shapes × 100 requests, 8 concurrent clients (and a 1-client variant), through HTTP.

## Experiment
Measure each query shape; explain the slow ones **as the application role** inside a tenant transaction; fix; measure again.

## Expected behavior
GIN-indexed full text and trigram matches in tens of milliseconds; the empty query (browse + facets over 180k rows) the slowest.

## Actual behavior

| Shape (200k store) | Matches | V15 p50 / p95, 8 clients | Final p50 / p95, 8 clients | Final p50, 1 client |
|---|---|---|---|---|
| common word `lamp` | 13,248 | 6,768 / 10,469 ms | 561 / 649 ms | 271 ms |
| 3 words | 423 | 12,600 / 14,654 ms | 89 / 106 ms | 44 ms |
| typo `lampshde` | 4,590 | 10,515 / 11,955 ms | 222 / 248 ms | 117 ms |
| rare word `teapot` | 9,206 | 9,874 / 12,126 ms | 363 / 462 ms | 180 ms |
| empty (browse + facets) | 180,000 | 4,477 / 7,109 ms | 651 / 804 ms | 339 ms |
| vendor filter | 20,000 | 1,870 / 2,300 ms¹ | 461 / 533 ms | 237 ms |
| price range + sort | 3,660 | 5,028 / 5,912 ms | 935 / 1,250 ms | 459 ms |
| word + vendor + price | 102 | 4,404 / 6,762 ms | 363 / 469 ms | 224 ms |

¹ The first run filtered on a vendor whose products were all drafts in the seed (0 matches); the final run uses one with 20,000.

**5,000-product store, 8 clients, final version:** every shape p95 ≤ 81 ms (p50 17–48 ms).

## What failed / why
Three separate problems, found one after another:
1. **RLS disabled the search indexes.** As superuser (no RLS) the full-text match took 27 ms; as the app role, 193 ms for full text and 2.6 s for trigram, with `Rows Removed by Filter: 190,036`. The plans used the GIN indexes **only for `store_id`**. Cause: `@@` (`ts_match_vq`) and `%>` (`word_similarity_op`) are not `LEAKPROOF`, and PostgreSQL won't evaluate a non-leakproof operator before the RLS policy, so it can't be an index condition. `uuid_eq` is leakproof, which is why `store_id` still was.
2. **Generic plans.** The first fix took 335 ms with literals but hit the 10 s statement timeout from the app: after five executions the JDBC prepared statement switched to a **generic plan**, which can't estimate the function's row count and nested-looped the match set against itself (175 million join-filter comparisons, 20 s).
3. **Computing more than the page needs.** Prices for all 13k–180k matches, and a hash join just to read product columns while browsing.

## How it was detected
The load script (timeouts, 500s with `QueryTimeoutException` in the logs), then `EXPLAIN (ANALYZE)` as superuser vs. `SET ROLE storeforge_app` + tenant setting, then `PREPARE` with `plan_cache_mode = force_generic_plan`.

## How the system recovered
N/A (local experiment). The statement timeout (P1) turned the worst case into an error instead of a stuck connection.

## What we changed
- **V16** `search_product_matches(query)`: SECURITY DEFINER (scanner role), UNION of the two index scans, returns ids and scores only, scoped by `current_store_id()` (`RowLevelSecurityIT`, mutation-checked: without the store condition it returned the other tenant's product).
- The match set is read once (generic plan ≈ custom plan: 367 vs 297 ms).
- Prices for every match only under a price filter or sort; otherwise for the 20-row page. Browsing reads products directly.
- **No cache** (milestone 13d): the realistic size is fast; the remaining slow case is browsing a 200k catalog ([ADR-025](../decisions/ADR-025-search-in-postgresql-and-no-cache.md)).
- **Lesson:** under RLS, look at plans as the application role. A superuser `EXPLAIN` hides exactly this problem.
