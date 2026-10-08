# Bulkheads and Head-of-Line Blocking

**Problem.** A queue of background work processed one item at a time is only as fast as its slowest item. When webhooks arrived, one slow receiver (8–10 s per request) made a verification email wait 32.8 s. Worse, jobs waiting in a local batch outlived their lease and were run twice ([EXP-012](../experiments/EXP-012-head-of-line-blocking.md)).

**Why we need it.** Any shared worker pool that mixes fast and slow work, or that calls dependencies with different reliability: emails, webhooks, payment events.

**Alternatives.**
- **One queue per job type with dedicated workers:** full isolation, but more processes, idle capacity, and more configuration.
- **Bigger batches and longer leases:** they move the cliff, they don't remove it.
- **Separate thread pools per type:** the classic bulkhead. A semaphore or counter per type on a shared virtual-thread executor gives the same isolation more cheaply.

**Decision.** Run jobs concurrently with a total limit and per-type limits, and claim only what can start now ([ADR-019](../decisions/ADR-019-concurrent-jobs-and-bulkheads.md)).

**Mental model.**
- **Head-of-line blocking:** items behind a slow one wait even though they could run. The cure is parallelism.
- **Parallelism needs bulkheads:** without limits, the slow kind of work takes *every* slot. That's head-of-line blocking again, just wider. A ship's bulkheads keep one flooded compartment from sinking the ship. A per-type limit keeps one slow dependency from sinking the worker.
- **Leases measure time since claim.** Never claim work you can't start immediately, or its lease expires while it waits, and someone else runs it too (at-least-once turns into "often twice").
- **Limits must be atomic under concurrency.** Reserve the slot (increment), then check. If over the limit, give it back. Check-then-increment lets two callers take the last slot.
- **Pick limits from the scarcest shared resource.** Here that's the DB pool (10): total 8, webhooks 4. Virtual threads make threads cheap, not connections.

**Project usage.** `JobWorker.start` (counters, release), `JobStore.claim(…, excludedTypes)`, `JobStore.release`, `storeforge.jobs.concurrency` and `type-concurrency`. Per endpoint: `WebhookSender`'s `maxConnPerRoute` is a second bulkhead.

**Failure cases.**
- Unbounded parallelism (pool exhaustion);
- limits checked non-atomically;
- releasing a claimed job without undoing its attempt count;
- tests that assumed "one round processes everything" (fixed with `runDueAt` loops).

**Testing.**
- `JobQueueIT.slowJobsAreBulkheadedAndDoNotDelayOtherWork`: 6 slow jobs with limit 2, plus 1 quick job. The quick job runs in the first round, at most 2 slow jobs ever run at once, and released claims don't count as attempts.
- Mutation W4 in [EXP-013](../experiments/EXP-013-webhook-mutations.md) (no type limit) fails it.
- EXP-012 measures before and after with real processes.

**Observability.** `storeforge_jobs_oldest_ready_age_seconds` (waiting work), executions by type, and email latency as the user-facing symptom. Per-type in-flight gauges would be a useful addition.

**When NOT to use it.** A queue with a single kind of uniform, fast work.
