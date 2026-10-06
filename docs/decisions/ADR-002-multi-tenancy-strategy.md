# ADR-002: Shared schema, store = tenant, PostgreSQL row-level security

- **Status:** Accepted, refined by [ADR-009](ADR-009-tenant-context-and-directory-tables.md) (directory tables without RLS; explicit tenant transactions)
- **Date:** 2026-10-06
- **Phase:** P0 (implementation in P3)

## Context
Thousands of small stores must coexist cheaply, with zero cross-tenant data leakage. The prompt listed both "Tenant" and "Store" as entities.

## Problem
(1) What is the tenant? (2) How is tenant data physically separated and protected?

## Options Considered
Tenant identity:
1. Tenant (organization) owning N stores, isolation at tenant level. Mirrors large platforms' "organizations", but adds a hierarchy before any use case needs it.
2. **Store is the tenant.** One concept, one isolation key (`store_id`).

Separation: shared schema + column; schema per tenant; database per tenant; hybrid (compared in blueprint 04).

## Decision
- **The store is the tenant.** An "organization above stores" is an optional future feature (it would group stores for billing/staff without changing the isolation key).
- **Shared database, shared schema, `store_id NOT NULL` on every tenant table**, protected in three layers:
  1. tenant-scoped repository signatures (`StoreId` parameter required);
  2. composite foreign keys `(store_id, id)`;
  3. PostgreSQL **RLS** (`ENABLE` + `FORCE`) with policies on `current_setting('app.current_store_id')`, set per transaction with `SET LOCAL`, for a runtime role that does not own the tables.

## Why We Chose It
- Cheapest per tenant and simplest operations (one migration run, one pool).
- Platform-wide queries (admin, analytics) remain possible.
- RLS turns "forgot a WHERE clause" from a data leak into an empty result.
- `store_id` is a natural future shard key: no transaction spans stores.

## Trade-offs
- Isolation is logical, not physical. A noisy tenant shares resources (mitigated by per-tenant rate limits and quotas).
- RLS adds per-query policy evaluation (small) and needs careful role setup and testing.
- The "SET LOCAL per transaction" discipline must be built into the transaction infrastructure.

## Consequences
- P1 already separates the **owner role** (migrations) from the **app role** (runtime DML) so RLS can be enabled in P3 without restructuring.
- Every new tenant table: `store_id`, leading index column, composite FKs, RLS policy. The RLS meta-test enforces this.
- Moving a large store to a dedicated database (hybrid) later requires a routing layer keyed by `StoreId`.
