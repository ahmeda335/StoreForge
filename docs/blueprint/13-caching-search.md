# 13 — Caching and 14 — Search

## Caching: principles

A cache exists to fix a **measured** latency or load problem. Each cache adds a consistency problem (staleness), a failure mode (cache down, stampede), and a security risk (cross-tenant key collision). Rule: no cache without a metric showing the need, a hit-rate metric after adding it, and a documented invalidation strategy.

**Never cache:** inventory availability for checkout decisions (the DB is the source of truth); cart contents; anything authorization-related for longer than seconds; payment state.

| Cache | What | Why | Where | TTL | Invalidation | Failure behavior |
|---|---|---|---|---|---|---|
| Store resolution | `hostname → storeId, status` | Every storefront request needs it; tiny, hot, rarely changes | **Caffeine** (local, per instance) | 60s | TTL + evict on `StoreDomainChanged`/`StoreSuspended` (in-process, after commit) | Miss → DB. Suspension takes effect ≤ 60s (accepted, documented) |
| Membership/permissions | `(userId, storeId) → permissions` | Checked on every admin request | Caffeine | 30s | Evict on role/membership change (local); TTL bounds staleness on other instances | Miss → DB |
| JWKS / signing keys | Public keys | Token verification | In-memory | Until rotation | Reload on unknown `kid` (rate-limited) | Keep last known keys |
| Storefront product page | Product + variants JSON (prices, not stock) | Hottest public read; expensive joins | **Redis** (shared) cache-aside, key `store:{id}:product:{handle}:v{catalogVersion}` | 5 min | Bump a per-store `catalogVersion` on `ProductUpdated` (key-versioning: no need to find and delete keys) | Redis down → read from DB (fail-open), with a circuit breaker so slow Redis doesn't add latency |
| HTTP caching | Storefront product/collection GETs | Let CDNs/browsers serve repeats | `Cache-Control: public, max-age=30, stale-while-revalidate=60` + `ETag` | 30s | ETag change | — |
| Rate-limit buckets | Counters | Must be shared across instances | Redis | Bucket window | n/a | Fail-open for storefront reads; **fail-closed for `/auth/*`** (in-memory fallback limiter) |
| Database | PG shared buffers | Built-in | — | — | — | Tune `shared_buffers`, check hit ratio before adding app caches |

Stampede protection: when a hot key expires, 1000 requests recompute it at once. Mitigations: Caffeine's `refreshAfterWrite`, request coalescing (single-flight per key), and TTL jitter.

Redis is introduced **in the developer-platform phase, for rate limiting**, a problem that genuinely requires shared state across instances. Product caching in Redis follows only if load tests show catalog reads as a bottleneck.

## Search

### Start with PostgreSQL

- `products.search_vector tsvector GENERATED ALWAYS AS (setweight(to_tsvector('simple', coalesce(title,'')),'A') || setweight(to_tsvector('simple', coalesce(vendor,'')||' '||coalesce(product_type,'')),'B') || setweight(to_tsvector('simple', coalesce(description,'')),'C')) STORED` + `GIN` index.
- `pg_trgm` GIN index on `title` for typo-tolerant prefix/similarity (`similarity(title, :q) > 0.3`).
- `unaccent` for accent-insensitivity (via a custom text search configuration).
- Query: `WHERE store_id = :s AND status = 'ACTIVE' AND search_vector @@ websearch_to_tsquery('simple', :q)` ranked by `ts_rank_cd` + recency.
- Filtering: price range, vendor, type, collection, in-stock (a join to inventory; or a denormalized `in_stock` flag updated by an event).
- Faceting: `GROUP BY vendor` / `product_type` counts on the filtered set. Fine for ≤ ~100k products per store.
- Consistency: **immediate**. Same transaction, so no index sync problem.

### When a dedicated engine (OpenSearch) is justified

- Faceted queries over large catalogs become slow (measure p95 > ~200ms on realistic data).
- Relevance features PG lacks: synonyms per store, language analyzers, "did you mean", per-field boosting at query time, learning-to-rank.
- Search load needs to scale separately from the transactional DB.

Cost: a second source of truth with **eventual consistency**. Index sync: `ProductUpdated` outbox event → indexer job → upsert document (with `version` as external version so out-of-order updates don't regress) + a periodic full reindex via alias swap (`products_v2` built, then the alias switched atomically). The UI must tolerate "just-saved product not yet searchable".
