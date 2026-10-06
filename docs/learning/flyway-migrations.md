# Flyway Migrations

**Problem.** The schema must evolve with the code, identically in every environment, and you must know exactly which changes each database has. Hand-run SQL or Hibernate `ddl-auto=update` drift and can't be reviewed or reproduced.

**Why we need it.** Every phase adds tables. Constraints are part of business correctness and must be versioned with the code that relies on them.

**Alternatives.** Liquibase (XML/YAML/SQL changelogs, rollback support, heavier); Hibernate DDL generation (no control, no history); manual scripts.

**Decision.** Flyway, plain versioned SQL ([ADR-006](../decisions/ADR-006-foundation-stack.md)).

**Mental model.** Files named `V{version}__{description}.sql` are applied once each, in order. Flyway records each one (version, checksum, who installed it, when) in `flyway_schema_history`. At startup it compares the files with the history: new versions are applied, and a changed checksum of an applied file aborts startup. History is immutable; you fix forward with a new migration.

**Project usage.** `src/main/resources/db/migration/`. Runs at app startup as `storeforge_owner` (`spring.flyway.user`), in schema `storeforge`. Placeholder `${app_role}` keeps the role name out of SQL.

**Configuration.** `spring.flyway.{user,password,default-schema,schemas,create-schemas,placeholders}` in `application.yml`.

**Failure cases.**
- Migration fails halfway: PostgreSQL runs DDL transactionally, so the migration rolls back and the app doesn't start. Fix the SQL and restart.
- Someone edits an applied migration: checksum mismatch at startup. Revert the edit and add a new migration.
- Long-locking DDL on a big table (e.g. `ALTER TABLE ... ADD COLUMN ... DEFAULT volatile`, or a non-concurrent index) blocks traffic. Use expand/contract and `CREATE INDEX CONCURRENTLY` (P16).
- Rolling deploys: old code runs against the new schema, so migrations must be backward compatible.

**Testing.** Every integration test run migrates an empty PostgreSQL from scratch. `DatabaseRolesIT.migrationsWereAppliedByTheOwnerRole` checks the history.

**Observability.** Startup INFO logs (`Migrating schema "storeforge" to version ...`), `flyway_schema_history`.

**When NOT to use it.** Schemaless stores. For our PostgreSQL, always.
