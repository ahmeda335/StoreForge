# EXP-012: One slow webhook receiver vs. the job worker

- **Date:** 2026-10-08 · **Phase:** P11
- **Setup:**
  - **two app instances** from the jar (ports 8080/8081 and 8082/8083), Compose PostgreSQL and Mailpit;
  - a local receiver that answers every webhook after **8 s** (`ThreadingHTTPServer`, logs event ids);
  - `WEBHOOKS_ALLOWED_PRIVATE_CIDRS=127.0.0.0/8`.

## Experiment
1. A merchant subscribes the receiver to `inventory_levels/update` and makes **30 stock adjustments**: 30 deliveries, 240 s of receiver time.
2. A new user registers right away (probe 1), and again 45 s later in the middle of the backlog (probe 2). Measure when their verification email reaches Mailpit.
3. After the backlog drains, count requests per event id at the receiver.
4. Fix the worker ([ADR-019](../decisions/ADR-019-concurrent-jobs-and-bulkheads.md)) and repeat identically.

## Expected behavior
- **Before:** the worker runs a claimed batch sequentially. Emails wait behind webhooks, and jobs waiting longer than their 60 s lease are reclaimed by the other instance, causing duplicates.
- **After:** emails are unaffected, and there are no duplicates.

## Actual behavior

| | Sequential worker (P9) | Concurrent + bulkheads (P11) |
|---|---|---|
| Probe 1 email latency | 6.1 s | **0.5 s** |
| Probe 2 email latency (mid-backlog) | **32.8 s** | **0.2 s** |
| Webhook requests / distinct events | 34 / 30 (**4 duplicates**) | 30 / 30 (**0**) |
| Receiver busy for | 131 s | 25 s |
| Queue right after the burst | 28 RUNNING (one instance's batch) | spread across both instances, 4 at a time each |

Side finding (before run): `Job failed; will retry: ProviderRejectedException: resource_missing` ×7. An old order from P10 experiments expired, and the cancellation of its payment kept failing because the in-memory fake provider no longer knew the intent.

## What failed / why
- **Head-of-line blocking:** the scheduler thread ran up to 50 claimed jobs one after another. Probe 1 was lucky (the other instance was between deliveries); probe 2 waited behind 8-second deliveries on both instances.
- **Duplicates:** instance A claimed 28 delivery jobs at once (lease 60 s), then needed 8 s each. After 60 s the leases of the jobs it hadn't reached yet expired. Instance B reclaimed and delivered them, and A later delivered them too.
- **Payments side finding:** `cancelForOrder` handled "already succeeded" (rejected cancel → retrieve) but not "unknown payment" (rejected retrieve), so the job retried until DEAD.

## How it was detected
The probe latencies, `jobsByStatus` (28 RUNNING on one instance), the receiver log's per-event counts, and WARN lines in the app logs.

## How the system recovered
Before the fix, all webhooks eventually arrived (4 twice), and emails were delayed but delivered. Receivers that dedup by event id were unaffected by the duplicates, which shows why the receiver checklist matters.

## What we changed
- **Worker:** virtual-thread execution with total and per-type limits; claims sized to free slots; over-claimed jobs released without counting an attempt; a non-blocking poll. Regression test `JobQueueIT.slowJobsAreBulkheadedAndDoNotDelayOtherWork`.
- **Payments:** a rejected retrieve during cancellation means the provider doesn't know the payment, so it's cancelled locally. Regression test `PaymentRecoveryIT.cancellingAnOrderWhosePaymentTheProviderDoesNotKnowCancelsItLocally`.
