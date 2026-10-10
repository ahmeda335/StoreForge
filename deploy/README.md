# Deploying StoreForge (P16)

One Docker host per environment (staging, production), each a Compose project ([ADR-031](../docs/decisions/ADR-031-single-host-production-and-delivery.md)). Locally both run side by side on different ports.

| | Staging | Production |
|---|---|---|
| Public (TLS) | `https://staging.storeforge.localhost:18443`, `https://<store>.staging.storeforge.localhost:18443` | `…production…:28443` |
| Grafana / Prometheus / Alertmanager / Mailpit (127.0.0.1) | `:13000` / `:19090` / `:19093` / `:18025` | `:23000` / `:29090` / `:29093` / `:28025` |

## First time on a host
1. Docker + Compose, Python 3 (smoke test).
2. `deploy/init-secrets.sh <env>`: generates passwords, keys and the ES256 signing key into `deploy/secrets/<env>/` (gitignored, never copied anywhere). On a real host, prefer the platform's secret store.
3. `deploy/deploy.sh <env> <image>`.

## Every release
`deploy/deploy.sh <env> <image@digest>`: migrate, then roll `api-1`, `api-2` and the worker, then the smoke test; automatic rollback on failure. CD runs the same script ([.github/workflows/deliver.yml](../.github/workflows/deliver.yml)). `deploy/deploy.sh <env> --rollback` goes back one release.

## Data
`deploy/backup.sh <env>` (daily base backup); WAL is archived continuously. `deploy/restore-drill.sh <env>` proves a restore works. Runbook: [deploy.md](../docs/operations/runbooks/deploy.md).

## Files
| File | What |
|---|---|
| `compose.yaml` | The environment: Caddy, api-1, api-2, worker, migrate (release job), PostgreSQL + wal-g, Redis, SeaweedFS, Mailpit, Prometheus, Alertmanager, Grafana |
| `Caddyfile` | TLS (`tls internal` locally; a DNS-challenge wildcard on a real domain), routing, health checks |
| `env/<env>.env` | Non-secret settings (domain, ports, subnet, proxy IP) |
| `postgres/` | PostgreSQL 17.10 with wal-g 3.0.9 (checksum-verified) |
| `prometheus/`, `alertmanager/`, `grafana/` | Scraping, operational rules (+ `infra/prometheus/slo-rules.yml`), routing, SLO dashboard |
| `deploy.sh`, `smoke.py`, `remote.sh` | Rollout with rollback; end-to-end smoke test; CD over SSH |
| `backup.sh`, `restore-drill.sh` | Base backups; restore drill with RTO |
