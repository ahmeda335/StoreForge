# PostgreSQL Locking, Conditional Updates and SKIP LOCKED

**Problem.** Many requests change the same few rows at once: stock of a popular item, a refresh token, the reservation a sweeper and a checkout both want. Without the right primitive you get lost updates (oversell), deadlocks, or workers blocking each other.

**Why we need it.** Inventory (P5), job queues (P9), payment state (P10), coupon limits (P13).

**Alternatives.** Application-level locks (single instance only); distributed locks in Redis (another failure mode); optimistic versioning (retry storms on hot rows); SERIALIZABLE (aborts + retries); **row locks taken by the right SQL statement**.

**Decision.** Single-statement conditional updates for counters; consistent lock order; `SKIP LOCKED` for work queues.

**Mental model.**
- **MVCC:** readers never block writers. Each statement sees a snapshot. That's why `SELECT` then `UPDATE` races: both transactions read the same snapshot value.
- **UPDATE takes a row lock.** If another transaction holds it, the UPDATE *waits*. Under READ COMMITTED, when the lock is released, PostgreSQL **re-evaluates the WHERE clause against the newly committed row version** ("EvalPlanQual"). So `WHERE on_hand - reserved >= 1` is checked against the latest data. The check and the write are atomic, with no window between them.
- **`affected rows`** is the answer: 1 means it happened, 0 means the condition failed. No exceptions are needed for the normal "out of stock" case.
- **Deadlocks** need a cycle: T1 holds X and wants Y, T2 holds Y and wants X. PostgreSQL detects it after `deadlock_timeout` (1s) and aborts one (`40P01`). Prevention: every transaction locks rows **in the same order** (we sort by variant id).
- **`FOR UPDATE SKIP LOCKED`:** "lock rows matching this, but skip any that someone else has locked". It turns a table into a work queue: N workers each claim different rows without waiting. The trade-off is that a skipped row is processed later, which is exactly right for background work and wrong for correctness checks.
- **Pool size bounds concurrency.** With 10 connections, at most 10 transactions race at once ([EXP-004](../experiments/EXP-004-oversell-three-ways.md)).

**Project usage.** `InventoryStore.tryReserve`, `adjustOnHand`, `transitionCheckout`, `expireBatch`; `RefreshTokenStore.consume` (P2); `MemberStore.lockStore` (P3, `FOR UPDATE` on a parent row).

**Failure cases.** Long transactions holding row locks (keep remote calls out of them!); lock waits piling up behind one hot row (measure in P15); forgetting the condition (`SET reserved = reserved + 1` without `WHERE available >= 1` relies on the CHECK constraint and throws instead of answering cleanly).

**Testing.** `InventoryConcurrencyIT` (100 buyers / 1 unit, opposite-order deadlock test), `OversellExperimentIT` (naive vs. conditional vs. SERIALIZABLE), concurrent sweepers in `InventoryReservationIT`.

**Observability.** `pg_stat_activity` (`wait_event_type = 'Lock'`), `pg_locks`, the `deadlocks` counter in `pg_stat_database`; from P6, long JDBC spans on hot rows.

**When NOT to use it.** Business logic with many steps and remote calls cannot be one SQL statement: use a short transaction with an explicit `FOR UPDATE` on a parent row, or a state machine with idempotent transitions.
