# EXP-001: Database unavailable (Phase 1 baseline)

- **Date:** 2026-10-06 · **Phase:** P1 · **Setup:** `docker compose --profile app up -d` (app container + postgres:17), Hikari pool 10, `connection-timeout` 3000 ms.

## Experiment
1. With the app running, `docker compose stop postgres`; probe readiness and liveness.
2. `docker compose start postgres`; poll readiness.
3. With postgres stopped, start a fresh app container.
4. `docker compose stop app` (SIGTERM).

## Expected behavior
1. Readiness → 503 quickly (≈ pool connection timeout); liveness stays 200 (no restart storm).
2. Readiness recovers without restarting the app.
3. The app refuses to start (exit ≠ 0) quickly with a clear error, so the orchestrator retries with backoff. It should not hang half-started.
4. Graceful shutdown: in-flight requests finish, then the pool closes.

## Actual behavior
| Step | Observed |
|---|---|
| 1 | `readiness HTTP 503 in 3.03s`, body `db: DOWN (CannotGetJdbcConnectionException)`; `liveness HTTP 200` |
| 2 | Readiness UP about 1s after PostgreSQL accepted connections; no app restart |
| 3 | Exit code 1 after about 3s: `Error creating bean ... Application run failed` (Flyway could not connect) |
| 4 | Logs: `Commencing graceful shutdown. Waiting for active requests to complete` → `Graceful shutdown complete` → Hikari `Shutdown completed` |

## What failed / why
Nothing unexpected. Note: readiness takes 3s to answer during an outage because it waits for the pool's `connection-timeout`. Orchestrator probe timeouts must be > 3s, or the probe itself times out. (It still ends up "not ready", but for a different reason.)

## How it was detected
Health endpoints on the management port; Hikari metrics (`hikaricp_connections_timeout_total` increments).

## How the system recovered
Automatically: Hikari creates new connections when the DB returns; readiness flips back.

## What we changed
Nothing in code. Documented the probe-timeout requirement here and in [operations/runbooks/database-unavailable.md](../operations/runbooks/database-unavailable.md).

## Revisit in
P6 (traces show the connection-acquire span failing), P9 (workers must back off, not spin), P17 (full experiment under load, with Toxiproxy latency instead of a clean stop).
