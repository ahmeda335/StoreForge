# SECURITY DEFINER Functions (Controlled Privilege Escalation in PostgreSQL)

**Problem.** Row-level security confines every query to one tenant. Some background work must first learn *which* tenants have work ("which stores have expired reservations?"). Giving the application a bypass would undo RLS for all code.

**Why we need it.** The reservation sweeper (P5). Later, the outbox relay and job scheduling (P9) and platform analytics.

**Alternatives.** See [ADR-011](../decisions/ADR-011-cross-tenant-background-work.md): iterate all stores, grant `BYPASSRLS` to the app, or a denormalized directory table.

**Decision.** A narrow `SECURITY DEFINER` function owned by a `NOLOGIN BYPASSRLS` role, returning store ids only.

**Mental model.**
- A normal function runs with the **caller's** privileges (`SECURITY INVOKER`). A `SECURITY DEFINER` function runs with its **owner's** privileges, like a setuid program.
- RLS exemptions: superusers, roles with `BYPASSRLS`, and table owners (unless `FORCE`). Our function's owner is a `BYPASSRLS` role that **cannot log in** and that the app role is **not a member of** (`SET ROLE storeforge_tenant_scanner` → permission denied, verified manually).
- **Pin `search_path`!** Inside the function, unqualified names are resolved via `search_path`. Without `SET search_path = storeforge, pg_temp`, a caller could create a `pg_temp.inventory_reservations` (or a function named like one the body calls) and make privileged code run *their* object. `pg_temp` goes last so temporary objects can never shadow real ones.
- **Least privilege on both ends:** `REVOKE EXECUTE ... FROM PUBLIC` (functions are executable by everyone by default) and `GRANT EXECUTE` only to the app role. The function body selects one column and returns ids, never rows.
- Ownership transfer needs: the migration role is a member of the scanner role, and the scanner role has `CREATE` on the schema. Both are granted in the provisioning script, because only a superuser can create `BYPASSRLS` roles.

**Project usage.** `V5__inventory.sql` (`stores_with_expired_reservations`), `infra/postgres/init/01-create-roles.sh`, `InventoryStore.storesWithExpiredReservations`, `ReservationSweeper`.

**Failure cases.** Forgetting `SET search_path` (privilege escalation). Returning data instead of ids. A "generic" definer function taking SQL or table names as parameters (a full RLS bypass). Old dev volumes without the scanner role (`docker compose down -v`).

**Testing.** `InventoryReservationIT` exercises the function end-to-end through the sweeper. `RowLevelSecurityIT` guarantees the tables themselves stay protected.

**Observability.** The sweeper logs and counts expired reservations (`storeforge_inventory_reservations_expired_total`).

**When NOT to use it.** Anything that returns tenant data, or anything a request path could abuse with user-controlled input.
