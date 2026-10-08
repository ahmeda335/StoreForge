# ADR-019: Concurrent job execution with per-type bulkheads; claim only what can start

- **Status:** Accepted (changes the execution model of [ADR-005](ADR-005-async-processing.md) / [ADR-016](ADR-016-async-infrastructure.md))
- **Date:** 2026-10-08
- **Phase:** P11
- **Evidence:** [EXP-012](../experiments/EXP-012-head-of-line-blocking.md)

## Context
The P9 worker claimed up to 50 jobs and ran them **one after another** on the scheduler thread. Every handler was fast until webhooks arrived: a delivery can take up to 10 s (the receiver's timeout).

## Problem
EXP-012, with two instances and one receiver taking 8 s per request:
- **Head-of-line blocking:** a verification email waited **32.8 s** behind webhook deliveries (normally ~0.5 s).
- **Lease expiry inside a batch:** jobs claimed with a 60 s lease waited in the local batch for minutes. The other instance reclaimed them as "crashed" and ran them too: **4 webhooks delivered twice**.

## Options Considered
1. Smaller batches and longer leases: this only moves the thresholds, and email still waits behind slow jobs.
2. A separate queue or worker per job type: isolation, but more moving parts and idle capacity.
3. **Concurrent execution, a total limit, per-type limits (bulkheads), and claims sized to free slots.**

## Decision
- **Execution:** handlers run on virtual threads, up to `storeforge.jobs.concurrency` (8, below the DB pool of 10: handlers hold connections only briefly, never during network calls).
- **Bulkheads:** `storeforge.jobs.type-concurrency` (`webhooks.deliver: 4`). One kind of slow work can never take every slot.
- **Claims:**
  - claim at most the free slots, excluding saturated types;
  - a job claimed beyond a limit is **released** at once (`attempts - 1`, as if never claimed);
  - limits are enforced lock-free (increment, then check), so concurrent claimers stay safe and `SKIP LOCKED` still separates them.
- **Polling and shutdown:**
  - the scheduled poll doesn't wait for the jobs it started;
  - `runOnce` (tests, tooling) does;
  - shutdown waits up to 20 s for running jobs.

## Why We Chose It
- **Measured** (EXP-012, same setup): email 0.5 s and 0.2 s mid-backlog, **0 duplicates**, and the backlog drained in 25 s instead of 131 s.
- **Fixes both problems at the root:** a job starts the moment it is claimed, so its lease never ticks while it waits.

## Trade-offs
- **More parallel load:** up to 4 concurrent requests per instance on a merchant's endpoint (also capped by the per-endpoint connection limit).
- **More parallel DB load** from handlers; watch pool `pending` (P15 load tests).
- **No per-tenant fairness yet:** one store's burst can still fill the webhook bulkhead for others. Noisy-neighbour work is planned for P15/P17.
- **Tests:** a single `runOnce` no longer drains a type over its limit. Tests that move time loop until idle (`runDueAt`).

## Consequences
- **New slow job types** get a type limit.
- **Metrics:** `storeforge_jobs_queue_depth` and the oldest-ready-age gauge still describe the queue. Per-type in-flight gauges are a possible addition.
