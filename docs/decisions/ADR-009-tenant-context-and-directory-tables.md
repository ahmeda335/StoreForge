# ADR-009: Tenant directory vs. tenant data, and an explicit tenant transaction

- **Status:** Accepted (refines [ADR-002](ADR-002-multi-tenancy-strategy.md))
- **Date:** 2026-10-06
- **Phase:** P3

## Context
ADR-002 chose shared schema + `store_id` + PostgreSQL RLS. Implementing it raised three questions the blueprint left open.

## Problem
1. Some queries must run **before** a tenant is known, or **across** tenants: "which stores is this user a member of?", "which store owns hostname X?", "is this user a member of store S?". RLS keyed on the current store makes those impossible.
2. How does the tenant id reach PostgreSQL, and how do we keep it from leaking between pooled connections?
3. When a user asks about a store they don't belong to, do we answer 403 or 404?

## Options Considered
For (1):
- RLS on every table, plus `SECURITY DEFINER` functions or a `BYPASSRLS` role for the cross-tenant lookups. That adds powerful escape hatches, which are exactly what attackers look for. And with `FORCE`, even the owner is filtered, so the definer functions need special policies.
- Additional policies keyed on `app.current_user_id`. They mix authentication state into data policies and double the policy surface.
- **Split tables into two categories:** a small *tenant directory* (`stores`, `store_domains`, `store_members`) without RLS, accessed only by the `stores` module, and *tenant data* (everything else) under RLS.

For (2): Hibernate `@TenantId`/filters (application-only, hidden); an AOP hook on every `@Transactional` (implicit, magical); **an explicit `TenantTransactions.inStore(storeId, work)`** that opens the transaction and runs `set_config('app.current_store_id', ..., true)`.

For (3): 403 (honest, but confirms existence) vs. **404** (no information to non-members).

## Decision
1. Directory/data split. The RLS meta-test enforces that every table with a `store_id` column has ENABLE + FORCE RLS and a policy, **except an explicit two-entry allow-list** (`store_domains`, `store_members`). `stores` has no `store_id` column.
2. `TenantTransactions.inStore` (shared kernel):
   - transaction-local `set_config`, which is the parameterizable `SET LOCAL`;
   - refuses to switch stores inside one transaction;
   - puts `storeId` in the log MDC.

   Authorization (`StoreAccess.require`) runs **inside** that transaction, because it reads roles (tenant data). Service methods look like this:
   ```java
   tenant.inStore(storeId, () -> {
       access.require(userId, storeId, Permission.STAFF_MANAGE);
       ...
   });
   ```
3. Non-members get **404 `store_not_found`**. Members lacking a permission get **403 `permission_denied`**. They already know the store exists.

Also decided:
- Invitation links carry `store` and `token` in the URL fragment, so acceptance runs inside the store's tenant transaction. No cross-tenant token lookup and no RLS bypass are needed.
- RLS policy helper `current_store_id()` uses `NULLIF(current_setting(..., true), '')`. A custom setting reads as `''` (not NULL) once it has been used in a session, and `''::uuid` would error.

## Why We Chose It
- No privileged bypass paths exist in the database at all.
- The tenant boundary is visible in every service method, so it can be reviewed by reading the code.
- `SET LOCAL` semantics make pooled-connection leakage impossible by construction (tested on a raw connection).

## Trade-offs
- Directory tables rely on application-level scoping only. This is mitigated by their small size, single-module ownership, and the meta-test allow-list.
- Every tenant operation pays one extra `set_config` round trip and one membership query. Measure in P15 and cache membership briefly if needed.
- Developers must use `inStore`. Forgetting it is safe (queries return nothing) but surprising. Documented in troubleshooting.

## Consequences
- New business modules (P4+) create only tenant-data tables: `store_id` column, composite FK to their parent, an RLS policy in the same migration, and every service method wrapped in `inStore`.
- Background jobs (P9) carry `store_id` and call `inStore` before touching tenant data.
- Flyway runs as the owner and FORCE applies to it, so future data-fixing migrations must set the tenant context or temporarily disable FORCE explicitly.
