# ADR-004: Atomic conditional updates + CHECK constraints for stock

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P0 (implementation in P5)

## Context
With `stock = 1`, two concurrent purchases must result in one success and one "insufficient stock", never `stock = -1`.

## Problem
Which concurrency-control mechanism protects inventory?

## Options Considered
Pessimistic `SELECT FOR UPDATE`; optimistic version + retry; **single-statement conditional update**; CHECK constraint only; Redis counters; SERIALIZABLE isolation (compared in blueprint 06).

## Decision
```sql
UPDATE inventory_items
   SET reserved = reserved + :qty, updated_at = now()
 WHERE store_id = :storeId AND id = :itemId
   AND on_hand - reserved >= :qty;      -- 1 row = reserved, 0 rows = insufficient
```
plus `CHECK (reserved >= 0 AND on_hand >= 0 AND reserved <= on_hand)` as the safety net, a reservation row and a ledger row in the same transaction, and multi-item reservations processed **in ascending item id order** to prevent deadlocks.

## Why We Chose It
- No read-modify-write gap: PostgreSQL row-locks the row for the UPDATE and, under READ COMMITTED, re-evaluates the `WHERE` against the latest committed version after waiting. The check and the write are one atomic step.
- No application retry loops (unlike optimistic locking under heavy contention).
- The invariant is enforced by the database even if application code is wrong.

## Trade-offs
- Hot items serialize on one row (inherent; acceptable at our scale; flash-sale experiment measures it).
- Error details (how many are available) need a separate read.

## Consequences
- Inventory code uses `JdbcClient` with explicit SQL (the SQL *is* the design here, so no ORM).
- Concurrency tests (100 threads, last item) are mandatory in P5.
