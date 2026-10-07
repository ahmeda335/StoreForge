# Runbook: Dead jobs / job backlog

## Symptoms
- `storeforge_jobs_queue_depth{status="DEAD"}` increased, or ERROR logs "Job exhausted its retries; moved to DEAD" / "Job failed permanently".
- `storeforge_jobs_oldest_ready_age_seconds` keeps growing (work waiting): workers down, stuck, or a flood.
- `storeforge_outbox_unpublished` not returning to ~0: the relay is not running.

## Diagnose
1. `curl -s localhost:8081/actuator/jobs | jq`: counts, oldest age, latest DEAD jobs with `type`, `storeId`, `attempts`, `lastError`.
2. Group DEAD jobs by `type` and `lastError`. One type failing everywhere points to a dependency (SMTP down, bug). One store only points to that tenant's data.
3. Follow one job in the logs: `jq 'select(.jobId=="<id>")'` (WARN per retry, ERROR at DEAD). The trace (`traceId`) links back to the request that produced the event.
4. Backlog without DEAD jobs: is scheduling enabled? Does the app log the poller? Check `storeforge_jobs_executions_total` rate and DB health (pool pending).

## Act
- Fix the cause first (dependency, configuration, code). Then requeue: `curl -X POST -H 'Content-Type: application/json' -d '{}' localhost:8081/actuator/jobs/<jobId>` (fresh attempts, runs immediately).
- Requeuing is safe because handlers are idempotent (dedup keys, inbox).
- `PermanentJobFailure` jobs (missing entity, bad input) usually should **not** be requeued. Investigate the producer.

## Notes
- The endpoint is on the private management port only.
- Retry schedule: 5 s, 10 s, 20 s… capped at 1 h, ±5 s jitter; DEAD after 10 attempts (~2.5 h of retrying).
