# ADR-031: Production on one Docker host per environment; delivery by digest with smoke tests and automatic rollback

- **Status:** Accepted
- **Date:** 2026-10-10
- **Phase:** P16
- **Evidence:** [EXP-024](../experiments/EXP-024-production-like-delivery.md)

## Context
StoreForge needs to run somewhere real, be updated often without downtime, survive bad releases and lost data, and tell people when something is wrong. The blueprint's stage 1 is a single VM ([blueprint 19](../blueprint/19-infrastructure-cicd.md)). Managed platforms (container services, managed PostgreSQL) are stage 2.

## Problem
1. How do the app's roles, the proxy, the database and observability run together?
2. How does a new version reach users without errors, and leave again when it's bad?
3. How are secrets provided?
4. How is data protected, and how do we know the backups work?

## Options Considered
1. **Runtime:** Kubernetes; a managed container service; **Docker Compose per environment on one host**.
2. **Proxy:** nginx; Traefik; **Caddy** (automatic TLS, active health checks, sane forwarding headers).
3. **Rollout:** stop/start (downtime); blue/green (double resources); **rolling, one API instance at a time behind the proxy**.
4. **Secrets:** environment variables; **files** (Docker secrets → Spring configtree).
5. **Backups:** nightly `pg_dump`; **continuous WAL archiving + base backups (wal-g) to object storage**; managed PITR.

## Decision
- **Runtime:**
  - one Compose project per environment ([deploy/](../../deploy/README.md));
  - Caddy (TLS, wildcard store hosts, a storage host for presigned URLs) in front of `api-1` and `api-2`;
  - a separate `worker`; a `migrate` release job;
  - PostgreSQL with WAL archiving, Redis, SeaweedFS, Prometheus, Alertmanager, Grafana.
- **One image, three roles** (`api`, `worker`, `migrate`); API and worker hold no owner credentials and refuse an older schema ([SchemaVersionGuard](../../src/main/java/dev/storeforge/shared/release/SchemaVersionGuard.java)).
- **Delivery:**
  - CI green, then the image is built once and pushed by digest (with provenance and SBOM);
  - `deploy.sh` runs: migrate (abort before rollout) → `api-1` → `api-2` → worker, each waiting for health → smoke test through the proxy;
  - any failure redeploys the previous image;
  - staging deploys automatically, production after approval;
  - migrations are backward compatible (expand/contract), so rollbacks never reverse them.
- **Secrets:** files under `/run/secrets`, named after the variables they replace, generated per environment; `ALLOW_DEVELOPMENT_SECRETS=false`; no ephemeral signing keys; only the proxy is trusted for forwarded headers.
- **Data:**
  - WAL archived continuously (`archive_timeout` 60 s ⇒ RPO ≤ ~1 min), base backups daily;
  - a restore drill script that restores next to the live database and compares counts;
  - a separate storage identity that can only touch the backups bucket.
- **Alerting:** the P15 SLO burn-rate rules plus operational rules (instance down, no worker, dead jobs, sustained load shedding); Alertmanager pages (at once) vs tickets (batched); an SLO dashboard.

## Why We Chose It
- **Fewest moving parts that still teach the production problems:** TLS, health-checked rolling deploys, release jobs, secrets, PITR, alert routing. Kubernetes would add a cluster to operate for one API and one worker.
- **Measured** (EXP-024): a deploy under traffic had 0 errors; broken releases never reached users (stopped at migrate, or rolled back with 0 errors); a restore reproduced the live counts in 10 s; an instance outage paged in 2 min 17 s.
- **Same script by hand and in CD:** the path that's practised is the path that runs.

## Trade-offs
- **One host is one failure domain** (hardware, disk, network): no high availability. Stage 2 is managed PostgreSQL and two hosts or a container service.
- **The proxy is the only entry point** and isn't replicated.
- **Login limits and storefront limits live in Redis on the same host;** losing Redis loosens them (fail open, ADR-020).
- **Alert emails go to Mailpit locally;** real routing (pager or chat) is configuration.

## Consequences
- Each migration in review: "does the previous release still work with it?".
- Backups are scheduled on the host, and their age should become a metric and an alert (open item).
- Moving to a managed platform changes `deploy.sh`, not the app: roles, health endpoints, secrets as files and digest-based delivery carry over.
