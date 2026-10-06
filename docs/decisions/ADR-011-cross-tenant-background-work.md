# ADR-011: Cross-tenant background work through a store-id-only definer function

- **Status:** Accepted (refines [ADR-009](ADR-009-tenant-context-and-directory-tables.md))
- **Date:** 2026-10-06
- **Phase:** P5

## Context
The reservation sweeper must find expired reservations in **all** stores. Under RLS a transaction only sees one store, and ADR-009 refused to give the application any RLS bypass.

## Problem
How does platform-wide background work discover *which tenants* need work, without weakening tenant isolation for everything else?

## Options Considered
1. **Iterate all stores** from the `stores` directory and sweep each in its own tenant transaction. Simple and RLS-pure, but O(number of stores) transactions per run even when only one store has work.
2. **Give the app role `BYPASSRLS`** (or connect a worker as the owner). That nullifies RLS for all code running under that role.
3. **A denormalized directory table** of reservation expiries without RLS. That duplicates data and adds a second write on every reservation.
4. **A `SECURITY DEFINER` function owned by a dedicated `NOLOGIN BYPASSRLS` role** that returns only store ids. The work itself then runs per store in normal tenant transactions.

## Decision
Option 4:
- Role `storeforge_tenant_scanner` (NOLOGIN, BYPASSRLS) is created by the provisioning script (only a superuser can grant BYPASSRLS).
- Function `stores_with_expired_reservations(max)`:
  - `SECURITY DEFINER`, `STABLE`;
  - **`SET search_path = storeforge, pg_temp`**, so a caller cannot hijack name resolution inside the elevated context;
  - `EXECUTE` revoked from `PUBLIC` and granted only to the app role;
  - returns `DISTINCT store_id` only.
- Sweeper: for each returned store, `TenantTransactions.inStore` → claim with `FOR UPDATE SKIP LOCKED` → expire, settle, ledger.

## Why We Chose It
- The privileged surface is one read-only query whose output is a list of ids. No tenant data crosses the boundary.
- Cost is proportional to stores **with work**, not all stores.
- The pattern generalizes to the job queue (P9): find the work's tenants, then process each inside its tenant context.

## Trade-offs
- A role that the application cannot become, but which owns code the application runs. Reviewing every definer function becomes part of security review (a checklist item).
- The provisioning script changed: existing local volumes must be re-created (`docker compose down -v`).
- `now()` in the function (database clock) vs. the app clock for the actual expiry: a reservation can be listed a moment before the app considers it expired. That is harmless; it is then simply not claimed.

## Consequences
- Each new cross-tenant need gets its own narrow definer function (never a generic "query anything" function), reviewed against this ADR.
- P9's `jobs` table carries `store_id` and is *infrastructure* (not tenant data). Workers claim jobs, then switch into the job's tenant context.
