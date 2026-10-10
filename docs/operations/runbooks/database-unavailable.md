# Runbook: Database unavailable

## Symptoms
- `/actuator/health/readiness` → 503, component `db: DOWN`.
- `hikaricp_connections_timeout_total` increasing; `hikaricp_connections_pending` > 0.
- Clients get **503 `service_unavailable`** with `Retry-After: 5` (not 500). Logs: WARN "Database unavailable: answering 503" with the exception type (`CannotGetJdbcConnectionException`, `QueryTimeoutException`, …). ERROR stack traces here would mean a misclassified failure: report it.
- `AvailabilityBudgetFastBurn` pages after about a minute of a full outage under traffic.
- **Only checkout completions failing with 503 after ~10 s:** something holds a lock on `store_counters` or on inventory rows (see Diagnose 3); `statement_timeout` cuts the waiters.
- Liveness stays UP. This is by design: restarting the app does not fix the database.

## Diagnose
1. Is PostgreSQL running? `docker compose ps postgres` / managed DB console.
2. Can you connect as the app role? `psql -h <host> -U storeforge_app -d storeforge -c 'select 1'`.
3. If it is reachable but the pool is starved: look for long transactions:
   `SELECT pid, state, now()-xact_start AS age, query FROM pg_stat_activity WHERE datname='storeforge' ORDER BY age DESC;`
   (The app role's `idle_in_transaction_session_timeout=30s` kills idle-in-transaction sessions automatically.)
4. `max_connections` exhausted? `SELECT count(*) FROM pg_stat_activity;` vs. `SHOW max_connections;` (instances × pool size must stay below it).

## Act
- Restore the database. The app reconnects by itself, **2–3 s after PostgreSQL accepts connections** (EXP-025). After a crash, PostgreSQL itself may need 3–20 s of recovery: watch its log for "ready to accept connections".
- **A blocking lock:** terminate the holder (`SELECT pg_terminate_backend(<pid>)`) after checking what it is; the blocked checkouts were rolled back and clients can retry.
- Do **not** restart app instances in a loop; it adds connection storms on recovery.

## Notes
- Readiness answers in about 3s during an outage (pool `connection-timeout`), so probe timeouts must be > 3s.
- Driver timeouts (`connectTimeout` 3 s, `loginTimeout` 5 s, `socketTimeout` 30 s) keep a stuck connection attempt from stalling the pool's recovery; without them it took ~41 s.
- Evidence: [EXP-001](../../experiments/EXP-001-database-unavailable-p1.md), [EXP-025](../../experiments/EXP-025-failure-suite-on-staging.md) (kill, latency, lock under traffic).
