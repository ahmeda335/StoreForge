# Runbook: SLO burn-rate alerts (latency or availability)

## Symptoms
- `AvailabilityBudgetFastBurn` / `SlowBurn`: 5xx are spending the error budget too fast (label `api`).
- `LatencyBudgetFastBurn` / `SlowBurn`: too many requests slower than the objective (storefront 200 ms, admin/developer 300 ms).

## Diagnose
1. **Scope:** which `api`? All of them (database, JVM, host) or one (a query, a dependency)?
2. **Change:** a deploy, a migration, a traffic spike (`rate(http_server_requests_seconds_count[5m])` by uri), one tenant (logs by store), a bulk import?
3. **Database:**
   - top statements by total time: `SELECT calls, total_exec_time, mean_exec_time, left(query, 120) FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10`;
   - lock waits: `pg_stat_activity` with `wait_event_type = 'Lock'`;
   - then `EXPLAIN (ANALYZE, BUFFERS)` **as the app role in a tenant transaction** (EXP-018).
4. **Pool:**
   - `hikaricp_connections_pending > 0` or a rising `hikaricp_connections_acquire_seconds` means requests are waiting for a connection, not for the database;
   - `hikaricp_connections_usage_seconds` shows long holders (a network call inside a transaction?).
5. **JVM:** GC pauses (`jvm_gc_pause_seconds`), CPU, live threads. For a CPU-bound slowdown, take a 30 s flame graph: `asprof -d 30 -e cpu -f /tmp/flame.html <pid>` ([load/README](../../../load/README.md)).
6. **Dependencies:** Redis DEGRADED (limits only, ADR-020); storage (uploads only); payment provider breaker (checkout payments).

## Act
- **Bad deploy:** roll back first, investigate after.
- **One expensive query:** a missing index or a bad plan. `ANALYZE` the table, fix forward with a migration; a statement timeout (10 s) caps the damage meanwhile.
- **Pool exhaustion with an idle database:** find the long holder (transaction around remote work) before raising the pool size.
- **One tenant's traffic:** storefront limits are per address. A store-level limit or suspension (platform API) is the escalation.
