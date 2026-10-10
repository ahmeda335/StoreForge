# Runbook: Deploys, rollbacks, backups and restores

Design: [ADR-031](../../decisions/ADR-031-single-host-production-and-delivery.md). Stack: [deploy/](../../../deploy/README.md).

## Normal deploy
CD does it: CI green on `main` → image pushed by digest → staging deploy → (approval) → production deploy. By hand, same script:

```
deploy/deploy.sh staging    ghcr.io/<owner>/storeforge@sha256:...
deploy/deploy.sh production ghcr.io/<owner>/storeforge@sha256:...
```

1. supporting services up, buckets ensured;
2. **migrate job** (stops here on failure; nothing is rolled out);
3. `api-1` replaced and healthy, then `api-2`, then the worker (Caddy routes around the one restarting);
4. smoke test through the proxy;
5. recorded in `deploy/state/<env>/current` and `previous`.

Any failure after step 2 rolls back to the previous image automatically (no reverse migration: see "Migrations").

## Symptoms
- `deploy.sh` printed `ROLLING BACK`, or `ROLLBACK FAILED`.
- `InstanceDown` or `NoWorkerRunning` alert after a deploy.
- Smoke test failing on an environment that was working.

## Diagnose
- **Which step:** the deploy output is timestamped per step. Migration logs: `deploy/state/<env>/migrate.log`.
- **Why a container is unhealthy:** `docker compose -p storeforge-<env> --env-file deploy/env/<env>.env -f deploy/compose.yaml logs --tail 200 api-1`. Common causes:
  - **"Refusing to start with development secrets":** a secret file is missing ([secrets runbook](secrets.md));
  - **"Database schema is at V… but this release needs V…":** the migrate job didn't run;
  - a configuration typo.
- **What's running:** `... ps --format '{{.Service}} {{.Image}} {{.Status}}'`.

## Act
- **Roll back** (to the previously deployed image): `deploy/deploy.sh <env> --rollback`.
- **ROLLBACK FAILED:** the previous image doesn't start either. That's usually environment, not code: database, secrets, disk. Fix it, then deploy the last known good digest by hand.
- **Never** fix production by editing containers; deploy a new image.

## Migrations: why rollbacks don't need reverse migrations
Every migration must work with the PREVIOUS release's code too (**expand/contract**):
- **add** a column or table in release N (old code ignores it);
- **use** it in N+1;
- **remove** the old one in N+2, once no running release reads it.

A rollback then runs old code on the newer schema, which `SchemaVersionGuard` allows ("newer is fine"). Destructive changes (drop, rename, type change) are split this way in review.

## Backups and restore
- **Continuous:** WAL archived to object storage by PostgreSQL itself (`archive_command`, `archive_timeout` 60 s ⇒ RPO ≤ ~1 min).
- **Daily:** `deploy/backup.sh <env>` takes a base backup. Schedule it on the host (systemd timer or cron), and alert if it's missing (open item: a backup-age metric).
- **Drill (monthly, and after any backup change):** `deploy/restore-drill.sh <env>` restores the latest base backup plus WAL next to the live database and compares counts. Record RTO in [EXP-024](../../experiments/EXP-024-production-like-delivery.md).
- **Real restore** (data loss or corruption):
  1. stop the app containers (`... stop api-1 api-2 worker`);
  2. move the old volume aside, don't delete it;
  3. restore as the drill does, but into the `postgres-data` volume, optionally with `recovery_target_time` just before the incident;
  4. start PostgreSQL, check, then start the app.
