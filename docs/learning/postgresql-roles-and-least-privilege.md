# PostgreSQL Roles and Least Privilege

**Problem.** If the application connects as a superuser or as the table owner, any bug or SQL injection can `DROP TABLE`, `ALTER` the schema, or read everything. In a multi-tenant system it is worse: owners and superusers **bypass row-level security**, so tenant isolation enforced by RLS (P3) would silently not apply.

**Why we need it.** StoreForge's tenant isolation relies on RLS ([ADR-002](../decisions/ADR-002-multi-tenancy-strategy.md)). RLS only constrains roles that don't own the tables.

**Alternatives.** One user for everything (simple, unsafe). Schema-per-tenant users (doesn't fit the shared-schema model). Application-only checks (no defense in depth).

**Decision.** Three roles ([ADR-006](../decisions/ADR-006-foundation-stack.md)): `postgres` (provisioning only), `storeforge_owner` (owns schema `storeforge`, runs migrations), `storeforge_app` (runtime DML only).

**Mental model.**
- A **role** is a user or a group. Objects (schemas, tables) have exactly one **owner**, who can do anything to them.
- Privileges are **granted** per object: `USAGE` on a schema lets you look inside it; `SELECT/INSERT/...` on a table lets you use rows.
- `GRANT ... ON ALL TABLES` only affects **existing** tables. `ALTER DEFAULT PRIVILEGES` affects tables **created in the future** by that role. That is why V1 sets default privileges: every later migration's tables are automatically usable by the app role, and nobody can forget a GRANT.
- Role-level settings (`ALTER ROLE ... SET statement_timeout`) apply to every new session of that role, which makes them a database-side safety net independent of application config.

**Project usage.** `infra/postgres/init/01-create-roles.sh` (roles, schema, timeouts) → `V1__baseline_privileges.sql` (default privileges) → `application.yml` (`spring.datasource.*` = app role, `spring.flyway.*` = owner role).

**Configuration.** Passwords come from env vars (`DB_APP_PASSWORD`, `DB_OWNER_PASSWORD`); local defaults are dev-only values.

**Failure cases.**
- A migration needs superuser rights (e.g. an untrusted extension): it fails. Provision the extension in infra instead.
- The volume pre-dates the init script: roles are missing. Re-provision (`docker compose down -v`).
- `statement_timeout` cancels a legitimately long query (SQLSTATE `57014`): raise it for that transaction with `SET LOCAL statement_timeout`, deliberately.

**Testing.** `DatabaseRolesIT`: `current_user` is the app role; `CREATE TABLE` fails with `42501 insufficient_privilege`; migrations recorded as installed by the owner; timeouts visible via `SHOW`.

**Observability.** `pg_stat_activity` shows `usename` per session; app sessions must all be `storeforge_app`.

**When NOT to use it.** Throwaway prototypes. For anything multi-tenant or production-bound, always separate roles.
