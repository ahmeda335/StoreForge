# A Job Queue on PostgreSQL (SKIP LOCKED, leases, backoff, dead letters)

**Problem.** Background work (send this email, call this webhook) must run reliably: it survives restarts, retries when a dependency is down, never runs on two workers at once in the normal case, and doesn't vanish when it keeps failing.

**Why we need it.** Every consumer of the outbox (P9), payments follow-up (P10), webhook deliveries (P11).

**Alternatives.** In-memory executors (lost on restart); RabbitMQ/SQS (another system to run; enqueue not atomic with DB writes); Kafka (a log, not a work queue; heavy); libraries like JobRunr or db-scheduler (good, but hide exactly what this project wants to learn).

**Decision.** A `jobs` table, `FOR UPDATE SKIP LOCKED` claims, leases, exponential backoff with jitter, a DEAD status, an operator endpoint ([ADR-005](../decisions/ADR-005-async-processing.md)).

**Mental model.**
- **Claim = one short committed transaction:** `UPDATE … SET status='RUNNING', lease=now+60s WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED LIMIT n) RETURNING …`. `SKIP LOCKED` lets 4 workers take 4 *different* batches without waiting. Without it they all claim the same rows (mutation-tested: a job ran 4×).
- **Work happens outside that transaction.** A slow handler holds no row locks and no pooled connection.
- **Lease = "mine until …".** If the worker dies, nobody calls `markDone`. Once `locked_until` passes, any worker may reclaim the job. That's why handlers must be idempotent: a reclaimed job may have half-run.
- **Ownership on finish:** `markDone … WHERE locked_by = me`. A worker whose lease expired must not overwrite the state set by the worker that reclaimed the job.
- **Backoff:** `5s·2^(n-1)` capped at 1 h, plus random jitter. Exponential gives a struggling dependency room. Jitter avoids synchronized retry storms after an outage.
- **Dead letters:** after 10 attempts (or a `PermanentJobFailure`), the job goes to `DEAD`, with an ERROR log and a metric. A human inspects it via `/actuator/jobs` and requeues after fixing the cause. Never drop silently.
- **Watch age, not depth:** `oldest ready age` says "work is waiting too long", whatever the queue size.

**Project usage.** `jobs.internal.JobStore` (all SQL), `JobWorker`, `Backoff`, `JobMetrics`, `JobsEndpoint`, `Housekeeping`.

**Failure cases.** Long handlers exceeding the lease (another worker starts the same job: size the lease to the slowest handler, or extend it); poison jobs (they go to DEAD instead of looping); table bloat (housekeeping + autovacuum); one tenant flooding the queue (no fairness yet); a log line that's never written ([EXP-009](../experiments/EXP-009-smtp-outage-and-dropped-logs.md): a duplicate MDC key made the JSON encoder drop retry/DEAD logs. Now asserted in tests).

**Testing.** `JobQueueIT`: retry timeline with controlled `now`, DEAD + requeue, permanent failure, lease reclaim after a simulated crash, 4 concurrent workers → each job once, log lines present. `BackoffTest`.

**Observability.** `storeforge_jobs_queue_depth{status}`, `storeforge_jobs_oldest_ready_age_seconds`, `storeforge_jobs_executions_total{type,outcome=done|retry|dead}`, job spans with `storeforge.job.type`/`attempt`, logs with `jobId`/`jobType` (MDC).

**When NOT to use it.** Very high throughput (thousands/s) or many independent consumer services: a broker earns its keep there (P18, after measuring). Work that needs strict ordering per key needs extra design (per-key serialization).
