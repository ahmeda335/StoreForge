# Zero-Downtime Deploys, Release Jobs and Expand/Contract Migrations

**Problem.** Replacing running code drops in-flight requests. New code can meet an old schema, or old code a new one. A bad release must be undone faster than it can be debugged.

**Why we need it.** P16: frequent releases without maintenance windows.

**Alternatives.**
- **Stop-start:** downtime.
- **Blue/green:** two full stacks; instant switch, but double resources and a shared database anyway.
- **Rolling behind a health-checking proxy:** one instance at a time.
- **Canary:** rolling plus a gradual traffic share and automated analysis.

**Decision.** Rolling, with a migrate job first, health gates, smoke tests and automatic rollback ([ADR-031](../decisions/ADR-031-single-host-production-and-delivery.md)).

**Mental model.**
- **Three ingredients make a restart invisible:**
  - the proxy stops sending traffic to an instance that isn't ready (active health checks);
  - the instance finishes in-flight requests before exiting (graceful shutdown on SIGTERM);
  - the proxy retries a request that couldn't reach an instance on another one.
- **Migrations are a separate release step,** run once, before any new code serves. If it fails, nothing changed for users.
- **Expand/contract:** during a rollout, old and new code run against the same schema, and after a rollback, old code runs against the new schema. So every migration must be compatible with the previous release:
  1. add (nullable column, new table);
  2. write and read in the next release;
  3. drop only in a later one.

  Renames are add + copy + switch + drop.
- **Guard the order:** new code refuses an older schema (`SchemaVersionGuard`); old code on a newer schema is fine by construction.
- **Deploy what you tested:** build once, deploy by digest (`image@sha256:…`), never a moving tag.
- **Rollback is a deploy of the previous digest,** practised by the same script.

**Project usage.** `deploy/deploy.sh`, Caddy health checks and `lb_try_duration`, `server.shutdown=graceful`, `migrate` profile, `SchemaVersionGuard`, `deliver.yml`.

**Failure cases.**
- Migrating at application startup with N instances (concurrent migrations, new schema before the old code is gone);
- destructive migrations in one release;
- readiness that doesn't fail during shutdown;
- smoke tests that skip the proxy;
- rolling forward through a failure.

**Testing.**
- Deploy under traffic, counting user-visible errors.
- Two broken images, one stopped at migrate and one rolled back.
- `SchemaVersionGuardTest`.

**Observability.** The deploy log per step; `InstanceDown`; error and latency burn rates around each deploy (annotate deploys on the dashboard).

**When NOT to use it.** Changes that can't be made compatible (a data migration rewriting every row, a protocol break). Plan a maintenance window, or a multi-step migration with a background backfill job.
