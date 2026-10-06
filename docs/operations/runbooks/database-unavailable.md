# Runbook: Database unavailable

## Symptoms
- `/actuator/health/readiness` → 503, component `db: DOWN`.
- `hikaricp_connections_timeout_total` increasing; `hikaricp_connections_pending` > 0.
- ERROR logs with `CannotGetJdbcConnectionException`.
- Liveness stays UP. This is by design: restarting the app does not fix the database.

## Diagnose
1. Is PostgreSQL running? `docker compose ps postgres` / managed DB console.
2. Can you connect as the app role? `psql -h <host> -U storeforge_app -d storeforge -c 'select 1'`.
3. If it is reachable but the pool is starved: look for long transactions:
   `SELECT pid, state, now()-xact_start AS age, query FROM pg_stat_activity WHERE datname='storeforge' ORDER BY age DESC;`
   (The app role's `idle_in_transaction_session_timeout=30s` kills idle-in-transaction sessions automatically.)
4. `max_connections` exhausted? `SELECT count(*) FROM pg_stat_activity;` vs. `SHOW max_connections;` (instances × pool size must stay below it).

## Act
- Restore the database. The app reconnects by itself; readiness returns within seconds.
- Do **not** restart app instances in a loop; it adds connection storms on recovery.

## Notes
- Readiness answers in about 3s during an outage (pool `connection-timeout`), so probe timeouts must be > 3s.
- Evidence: [EXP-001](../../experiments/EXP-001-database-unavailable-p1.md).
