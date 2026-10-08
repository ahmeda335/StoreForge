# Caching: Measure First

**Problem.** Caches are the easiest performance fix to add and the hardest to keep correct: stale data, invalidation, stampedes, memory, and one more system that can fail.

**Why we need it.** Blueprint 13 lists caching for P13 "only if measured". Milestone 13d is the decision not to add one yet.

**Alternatives.**
- **Cache search responses** in Redis keyed by query: low hit rate (long tail of queries), invalidation on every product change.
- **Cache per-store facets/browse results** keyed by a catalog version: high hit rate, simple invalidation (bump the version on write).
- **Make the query cheaper:** often enough, and nothing to invalidate.
- **No cache.**

**Decision.** No cache; fix the queries ([ADR-025](../decisions/ADR-025-search-in-postgresql-and-no-cache.md), [EXP-018](../experiments/EXP-018-search-at-scale-under-rls.md)).

**Mental model.**
- **First find out where the time goes** (`EXPLAIN ANALYZE`, load test). In EXP-018 the 2–15 s came from RLS blocking index use, a generic plan and doing work for rows nobody shows. A cache would have hidden all three and served stale results, slowly, on every miss.
- **Set a budget and a realistic size:** p95 ≤ 300 ms at 8 concurrent clients; typical store 5k products → 81 ms. Then the question is "which case breaks the budget?" (browsing 200k products: 339 ms single client).
- **Cache-aside** when needed: read cache → miss → compute → store with TTL. Invalidate by **versioned keys** (`facets:{store}:{catalogVersion}`), not by deleting keys from many places.
- **Stampede:** when a hot key expires, all requests recompute at once. Protect with a per-key lock/single-flight or early refresh.
- **Fail open:** a cache outage must not become an outage (ADR-020).

**Project usage.** None in P13 by decision. Existing: Caffeine store resolution (P3), Redis for limits (P12).

**Failure cases.** Caching a bug; caching per-user or per-tenant data under a shared key (tenant leak); unbounded keys from user input; invalidation forgotten on one write path.

**Testing.** A cache needs tests for invalidation on every write path and for tenant separation of keys.

**Observability.** Hit ratio, load time on miss, evictions, size; latency with and without the cache.

**When NOT to use it.** When the slow part can be fixed, when data must be read-your-writes consistent (inventory, prices at checkout), or when the hit rate would be low.
