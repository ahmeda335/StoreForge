# EXP-004: Stock = 1, 50 buyers, three implementations

- **Date:** 2026-10-06 · **Phase:** P5 · **Setup:** `OversellExperimentIT`, PostgreSQL 17 (Testcontainers), Hikari pool **10**, 50 threads released by a latch. The naive variants sleep 20 ms (`pg_sleep`) between read and write to stand in for real work in that window.

## Experiment
The same race against:
1. **Naive read-modify-write**: `SELECT reserved, on_hand` → check in Java → `UPDATE SET reserved = <value computed in Java>`.
2. **Conditional update** (production, ADR-004): `UPDATE ... SET reserved = reserved + 1 WHERE on_hand - reserved >= 1`.
3. **Naive code under SERIALIZABLE** isolation.

## Expected behavior
1. Several buyers "succeed" for one unit (lost updates), while the counter shows only 1 reserved.
2. Exactly one success.
3. Exactly one success; the others abort with serialization failures (40001) that a real system would have to retry.

## Actual behavior (two runs, identical)
| Variant | Successes | Other outcomes | `reserved` afterwards |
|---|---|---|---|
| Naive | **10 of 50** | 40 saw `reserved = 1` and declined | 1 (but 10 customers were told "yours") |
| Conditional update | **1** | 49 × insufficient stock | 1 |
| SERIALIZABLE | **1** | **9 aborted** (Spring `CannotAcquireLockException` ← SQLSTATE 40001), 40 declined | 1 |

## What failed / why
- Naive: each transaction's write **overwrote** the others' (lost update). The counter looks correct while reality is oversold 10×. A CHECK constraint can't catch it, because no single row ever violates it.
- **Why exactly 10?** The connection pool has 10 connections. Only 10 transactions can be inside the race window at once; the other 40 waited for a connection and then read the already-updated row. **The pool size bounds the width of the race.** Larger pools or more instances make the oversell worse, and a tiny pool in dev can hide the bug entirely.
- SERIALIZABLE: PostgreSQL's SSI detected the read/write dependency cycle and aborted 9 of the 10 concurrent transactions. Correct, but a failure rate that callers must handle with retries.

## How it was detected
Invariant assertions (successes vs. stock) in the test, not exceptions: the naive version throws nothing.

## How the system recovered
N/A (experiment).

## What we changed
Nothing. This confirms ADR-004: conditional single-statement updates for hot counters. Lesson recorded: **test concurrency with production-like pool sizes**, because the pool shapes the race.
