# Row-Level Security and the Tenant Context

**Problem.** In a shared-schema multi-tenant database, every query on tenant data must filter by tenant. One forgotten `WHERE store_id = ?`, in a new endpoint, a report or a background job, leaks another merchant's data. That is the canonical SaaS security incident.

**Why we need it.** Application-level discipline (explicit `StoreId` parameters) catches most mistakes. A second, **independent** layer in the database catches the rest.

**Alternatives.** Application-only filtering (Hibernate filters / `@TenantId`, which raw SQL bypasses); schema- or database-per-tenant (stronger isolation, much higher operational cost; see [blueprint 04](../blueprint/04-multi-tenancy.md)); views per tenant.

**Decision.** PostgreSQL RLS on all tenant-data tables, with the tenant passed per transaction ([ADR-002](../decisions/ADR-002-multi-tenancy-strategy.md), [ADR-009](../decisions/ADR-009-tenant-context-and-directory-tables.md)).

**Mental model.**
- `ALTER TABLE roles ENABLE ROW LEVEL SECURITY` + `CREATE POLICY ... USING (store_id = current_store_id())`: PostgreSQL silently adds that predicate to **every** query on the table, including your forgotten one. `WITH CHECK (...)` does the same for rows being *written*, so you can't insert or move a row into another tenant.
- **Who is exempt:** superusers and roles with `BYPASSRLS` always are. Table **owners** are exempt unless the table has `FORCE ROW LEVEL SECURITY`. This is why the app connects as `storeforge_app`, a non-owner (P1).
- **The tenant input:** `set_config('app.current_store_id', '<uuid>', true)`. The `true` makes it transaction-local, like `SET LOCAL`. The policy reads it through `current_store_id()`. No setting means NULL, `store_id = NULL` is never true, and you see **zero rows**. That is fail-closed by default.
- **Why transaction-local:** pooled connections are reused by other requests. A session-level `SET` would survive on the connection and give the next request (maybe another tenant's) this tenant's context. Transaction-local settings vanish at COMMIT/ROLLBACK. The test `tenantContextEndsWithItsTransactionOnThePooledConnection` proves it on one physical connection.
- **The `''` gotcha:** once a custom setting has been used in a session, `current_setting(..., true)` returns `''` (not NULL) after the local value ends. `''::uuid` raises an error, hence `NULLIF(..., '')`.
- **Foreign-key checks bypass RLS.** That's how `store_members` (directory) can reference `roles` (RLS). It is also why composite FKs `(store_id, role_id)` matter: the FK itself guarantees same-store references.

**Project usage.** `V3__stores_tenancy.sql` (function, policies), `TenantTransactions` (shared kernel), every tenant service method (`tenant.inStore(storeId, () -> ...)`).

**Configuration.** None at runtime. The policies live in migrations, and every new tenant table must add one (the meta-test fails otherwise).

**Failure cases.**
- Forgetting `inStore`: the query returns nothing. That is confusing but safe. See troubleshooting.
- A data migration run as the owner sees nothing, because FORCE applies to the owner too. Set the context or disable FORCE explicitly in that migration.
- Policies with functions that are slow, or not `STABLE`, hurt every query. Keep the predicate an indexed equality (`store_id` leads every index).
- Leaking context across threads: the MDC and the transaction are per thread. Async work (P9) must re-establish both from the job's `store_id`.

**Testing.** `RowLevelSecurityIT`:
- the meta-test (every `store_id` table has ENABLE + FORCE + a policy, except the two-table directory allow-list);
- no context gives 0 rows;
- context A asking explicitly for B gives 0 rows;
- writing B's row under A is rejected;
- context ends with the transaction;
- no tenant switching;
- the audit log is append-only.

`TenantIsolationIT` checks the API layer.

**Observability.** `storeId` appears in every log line written inside `inStore`. Later (P6), it also appears as a span attribute.

**When NOT to use it.** Tables that must be queried across tenants by design (the tenant directory), and platform-level data (`users`). Keep that set tiny and owned by one module.
