# Database (as built)

Design: [blueprint/06-database.md](../blueprint/06-database.md). Decision: [ADR-006](../decisions/ADR-006-foundation-stack.md).

## Roles and schema

```mermaid
flowchart LR
    subgraph cluster[PostgreSQL 17 cluster]
      SU[postgres<br/>superuser: provisioning only]
      OW[storeforge_owner<br/>owns schema + all tables]
      AP[storeforge_app<br/>USAGE on schema, DML via default privileges]
      subgraph db[database storeforge]
        S[schema storeforge]
      end
    end
    SU -- "init script creates roles + schema" --> S
    OW -- "Flyway migrations (DDL)" --> S
    AP -- "runtime SELECT/INSERT/UPDATE/DELETE" --> S
```

| Role | Used by | Can | Cannot | Settings |
|---|---|---|---|---|
| `postgres` | `infra/postgres/init/01-create-roles.sh` only | Everything | — | Never used by the app |
| `storeforge_owner` | Flyway (`spring.flyway.user`) | DDL in schema `storeforge`; create trusted extensions | — | `search_path=storeforge` |
| `storeforge_app` | Application (`spring.datasource.username`) | DML on tables, use sequences, execute functions | CREATE/ALTER/DROP (SQLSTATE 42501, tested) | `search_path=storeforge`, `statement_timeout=10s`, `idle_in_transaction_session_timeout=30s` |

Why: least privilege, plus RLS in P3 only applies to non-owner roles ([ADR-002](../decisions/ADR-002-multi-tenancy-strategy.md)).

`public` schema: `CREATE`/usage revoked from `PUBLIC`; nothing lives there.

## Migrations

| Version | File | Purpose |
|---|---|---|
| V1 | `V1__baseline_privileges.sql` | Default privileges: every future table/sequence/function created by the owner is usable by the app role |
| V2 | `V2__identity.sql` | `citext` extension; `users`, `refresh_tokens`, `email_tokens` (see [domains/identity.md](../domains/identity.md)) |
| V3 | `V3__stores_tenancy.sql` | `current_store_id()`; directory tables `stores`, `store_domains`, `store_members`; RLS tables `roles`, `invitations`, `audit_log` (append-only for the app). See [domains/stores.md](../domains/stores.md) |

## Row-level security (P3)

| Table kind | Tables | Protection |
|---|---|---|
| Platform | `users`, `refresh_tokens`, `email_tokens` | Not tenant data |
| Tenant directory | `stores`, `store_domains`, `store_members` | Application scoping by the stores module only ([ADR-009](../decisions/ADR-009-tenant-context-and-directory-tables.md)) |
| Tenant data | `roles`, `invitations`, `audit_log` (+ every future business table) | `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy `tenant_isolation` `USING`/`WITH CHECK (store_id = current_store_id())` |

Checklist for every new tenant-data table: `store_id uuid NOT NULL` → indexes lead with `store_id` → `UNIQUE (store_id, id)` if children reference it → composite FKs to parents → ENABLE + FORCE RLS + `tenant_isolation` policy **in the same migration**. `RowLevelSecurityIT` fails the build if the RLS part is missing.

## Access patterns: JPA vs. JdbcClient

| Use | Tool | Example |
|---|---|---|
| Load/modify/save an aggregate | Spring Data JPA (`ddl-auto=validate`, `open-in-view=false`, `@Version`, `@DynamicUpdate`) | `User` |
| Statements whose SQL *is* the design (atomic conditional updates, `RETURNING`, counters) | `JdbcClient` with named parameters | `RefreshTokenStore.consume`, `UserLoginCounters.recordFailure` |

## Concurrency patterns in use

| Pattern | Where | Prevents |
|---|---|---|
| Unique constraint as arbiter | `uq_users_email` | Duplicate accounts under concurrent registration (check-then-insert race) |
| Conditional `UPDATE ... WHERE <still valid> RETURNING` | refresh tokens, email tokens | Double use of single-use tokens |
| Atomic increment `x = x + 1` | `failed_login_count` | Lost updates of the lockout counter |
| `@DynamicUpdate` | `User` | A stale entity overwriting columns updated by atomic SQL |
| Parent-row lock (`SELECT ... FOR UPDATE` on `stores`) | Owner changes | Write skew on "≥ 1 owner" ([EXP-003](../experiments/EXP-003-write-skew-last-owner.md)) |
| Composite FK `(store_id, x_id)` | `store_members`, `invitations` → `roles` | Cross-tenant references |

Migration rules (from the blueprint):
- `src/main/resources/db/migration/V{n}__snake_case_description.sql`; never edit an applied migration (checksum).
- Migrations run at startup in dev/test. In production they run as a separate step before rollout (P16).
- Expand → migrate → contract for breaking changes; `CREATE INDEX CONCURRENTLY` for large tables.
- Placeholder `${app_role}` is available (configured via `spring.flyway.placeholders.app_role`).

## Provisioning gotcha

The init script runs **only when the data volume is empty**. After changing it locally: `docker compose down -v && docker compose up -d` (this deletes local data).
