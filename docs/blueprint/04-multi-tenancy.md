# 04 — Multi-Tenancy

> The most important SaaS property: **one store must never see another store's data.**

## Strategies considered

| Strategy | Isolation | Cost per tenant | Ops complexity | Cross-tenant queries (admin/analytics) | Migrations | Fit |
|---|---|---|---|---|---|---|
| Shared DB, shared schema, `store_id` column | Logical (app + RLS) | Lowest | Lowest | Easy | One run | **Chosen** |
| Shared DB, schema per tenant | Stronger (search_path) | Medium | Thousands of schemas; catalog bloat; connection search_path bugs | Hard (UNION over schemas) | N runs, partial-failure states | Poor for many small tenants |
| Database per tenant | Strongest | Highest | Connection pools × N, provisioning automation | Very hard | N runs | Large enterprise tenants only |
| Hybrid (shared by default, dedicated DB for "enterprise" tenants) | Mixed | Mixed | Highest (routing layer) | Mixed | Mixed | Later stage evolution |

## Decision: shared schema + `store_id` + PostgreSQL Row-Level Security

See [ADR-002](../decisions/ADR-002-multi-tenancy-strategy.md). The **store is the tenant**: `stores.id` is the tenant identifier, and every tenant-owned table has a `store_id NOT NULL`.

Defense in depth, in three independent layers:

1. **Application layer:** every repository method on tenant data takes a `StoreId` argument explicitly. No method like `findById(UUID)` exists for tenant data. You *cannot* write the unsafe query by accident.
2. **Database schema:** composite foreign keys `(store_id, id)` prevent an order item referencing a variant from another store, even if the application code is buggy.
3. **Database RLS:** the application connects as role `storeforge_app`, which does **not** own the tables. Policies require `store_id = current_setting('app.current_store_id')::uuid`. The tenant context is set per transaction with `SET LOCAL`. A forgotten `WHERE store_id = ?` returns *zero rows* instead of another tenant's rows.

Why not rely only on Hibernate `@Filter`/`@TenantId`? They are application-level conveniences. They are bypassed by native queries and JDBC, and they hide the mechanism. RLS is enforced by the database for *every* access path.

## Tenant identification

| Entry point | How the store is identified | Trust |
|---|---|---|
| Storefront API | `Host` header → `store_domains` lookup (`acme.storeforge.localhost`, or a custom domain); fallback path `/storefront/v1/stores/{handle}` in dev | Public; store must be `ACTIVE` |
| Merchant Admin API | Path: `/admin/v1/stores/{storeId}/...` + membership check | Never from a header the client invents; membership verified server-side on every request |
| Developer API | Derived **from the credential** (API key / OAuth token is bound to one store) | Path store id must equal credential's store |
| Payment provider webhook | Our `payment_id` in provider metadata → payment row → store | Signature first, then lookup |
| Background job | `store_id` column on the job row | Set when enqueued inside a tenant transaction |
| Platform admin | Explicit `storeId` parameter; operations audited | Admin role only |

## Request context

```mermaid
sequenceDiagram
    participant F as TenantResolutionFilter
    participant S as Spring Security
    participant A as AuthorizationCheck
    participant TX as @Transactional service
    participant DB as PostgreSQL
    F->>F: resolve StoreId from host/path/credential
    S->>S: authenticate principal (user/app/customer)
    A->>A: principal has permission X in StoreId?
    TX->>DB: BEGIN; SET LOCAL app.current_store_id = '<id>'
    TX->>DB: queries (RLS filters by store_id)
    TX->>DB: COMMIT (setting disappears)
```

`TenantContext` is a request-scoped value (not a static `ThreadLocal` that leaks across pooled threads). A `TenantAwareTransaction` hook runs `SET LOCAL` at transaction start. `SET LOCAL` is used, never `SET`, because with connection pooling a session-level `SET` survives on the connection and would apply to the **next tenant's** request.

## Per concern

- **Authorization:** membership (`store_members`) + role permissions. Checked in the application service, not only in controllers.
- **Database queries:** explicit `store_id` predicate + RLS. Every tenant-table index starts with `store_id` (it is in almost every query).
- **Repository boundaries:** tenant repositories live in module internals. Their signatures require `StoreId`.
- **Background jobs:** `jobs.store_id` column. The worker opens a transaction with that store's context. Platform-wide jobs (reservation expiry sweep) use a dedicated `storeforge_worker` role with an explicit `BYPASSRLS`-free policy design: the sweep selects ids per store, and each store's work runs in its own tenant transaction.
- **Caching:** every key is prefixed `store:{storeId}:...`. A cache API that takes a `StoreId` parameter makes it impossible to forget.
- **Object storage:** keys `stores/{storeId}/products/{productId}/{uuid}.jpg`. Presigned URLs are generated only after an authorization check.
- **Search:** `store_id` is a mandatory filter in the search query builder (PG). An OpenSearch index would include `store_id` as a keyword field with a filtered alias per query.
- **Events:** every event envelope carries `storeId`. Consumers set the tenant context from it.
- **Webhooks:** subscriptions belong to a store. Delivery payloads only contain that store's data.
- **Logging:** `storeId` in the MDC → a JSON field on every log line.
- **Metrics:** **no** `store_id` label on high-volume metrics (cardinality explosion: 10k stores × routes × status). Use per-tenant counters only for top-N tracking and rate limiting (Redis), and put tenant id on **traces/logs**, where cardinality is cheap.
- **Rate limiting:** buckets keyed per store *and* per credential. One store's traffic cannot exhaust the global capacity (noisy neighbour).

## Preventing cross-tenant leakage: checklist

1. No tenant query without `StoreId` in the signature.
2. RLS enabled **and forced** (`FORCE ROW LEVEL SECURITY`) on every tenant table. The app role is not the owner.
3. Composite FKs `(store_id, x_id)` on child tables.
4. IDs are UUIDs (v7, time-ordered). They are non-guessable, which helps, but never *relied upon* for security.
5. A 404 (not a 403) for resources in other stores, so the API does not confirm existence.
6. Cache keys, object keys, and job payloads always include `storeId`.
7. Response DTOs are built from tenant-scoped reads only.

## Testing tenant isolation

- **Isolation integration test per module:** create store A and store B with data, authenticate as A, call every endpoint with B's resource IDs → expect 404 and *no* B data in any response body.
- **RLS test:** using the app DB role, with `app.current_store_id = A`, `SELECT * FROM <every tenant table>` must return only A rows. Without the setting, it must return zero rows or raise an error.
- **Schema meta-test:** query `information_schema` / `pg_class`. Every table with a `store_id` column must have RLS enabled and forced, and at least one policy. This fails the build if someone adds a table and forgets.
- **Architecture test:** repository methods in tenant modules must have a `StoreId` parameter (ArchUnit).
- **Cache key test:** the cache wrapper rejects keys without a store prefix.
- **Fuzz/IDOR test:** a randomized test swaps IDs across tenants in recorded API calls.

## Evolution to larger scale

1. **Hot tenant isolation:** move a very large store to its own database (hybrid). Routing table `store_id → datasource`. The application already passes `StoreId` everywhere, which is why explicit `StoreId` parameters pay off later.
2. **Sharding:** `store_id` is the natural shard key, because no transaction spans stores. Options include Citus (distributed PG with `store_id` as distribution column) or application-level routing over N PG clusters.
3. Platform-wide queries (admin, analytics) then move to an aggregated read store fed by events/CDC.
