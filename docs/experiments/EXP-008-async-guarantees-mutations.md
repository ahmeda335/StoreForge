# EXP-008: Do the async tests guard the mechanisms? (mutations)

- **Date:** 2026-10-07 · **Phase:** P9 · **Setup:** Testcontainers PostgreSQL; `ReliableEffectsIT` and `JobQueueIT`.

## Experiment
1. Remove the inbox check from `CustomerPurchaseStats` (always apply the increment).
2. Remove `FOR UPDATE SKIP LOCKED` from the claim query in `JobStore.claim` (4 workers, 60 jobs). Run 3 times.

## Expected behavior
1. A redelivered `OrderPaid` counts the purchase twice.
2. Concurrent workers select the same rows. The second worker's UPDATE waits for the first, then still matches by id and claims the job again, so it runs more than once.

## Actual behavior
| Mutation | Result |
|---|---|
| No inbox | `customerStatisticsCountEachPaidOrderOnce` failed: `ordersCount` **2** (expected 1) |
| No SKIP LOCKED | `concurrentWorkersNeverRunTheSameJobTwice` failed in **3/3** runs: a job executed **4×** (twice in one run, 2×) |

## What failed / why
Exactly the anomalies the mechanisms exist to prevent:
- an increment is not idempotent under at-least-once delivery;
- `UPDATE … WHERE id IN (subquery)` without row locks in the subquery lets every worker claim the same ids, because the outer UPDATE re-checks only the id, not the status.

## How it was detected
Invariant assertions (exact counts per job id, exact customer count).

## How the system recovered
N/A. Mutations reverted, suite green.

## What we changed
Nothing in production code. Recorded as evidence that the tests guard the inbox and the claim lock.
