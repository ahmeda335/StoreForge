# EXP-003: Write skew on "a store keeps at least one owner"

- **Date:** 2026-10-06 · **Phase:** P3 · **Setup:** `StaffManagementIT.concurrentMutualDemotionNeverLeavesTheStoreWithoutAnOwner`. The store has two owners; two threads released by a latch each demote the *other* owner at the same instant. PostgreSQL 17, READ COMMITTED.

## Experiment
Run the test (a) with the production code, and (b) with the `members.lockStore(storeId)` line removed from `MemberService.changeRole` (a deliberate mutation), five times each.

## Expected behavior
- (a) Exactly one demotion succeeds (204). The other is rejected with `409 last_owner`. One owner remains.
- (b) Both succeed and the store ends with **zero owners**. Each transaction counts 2 owners in its own snapshot, decides "safe to demote", and updates a *different* row, so neither blocks the other.

## Actual behavior
| Variant | Runs | Result |
|---|---|---|
| (a) with lock | every CI/local run | 204 + 409, one owner left ✅ |
| (b) lock removed | 5 | **5/5 failed**: both returned 204 (the assertion `[204, 409]` saw `[204, 204]`) |

## What failed / why
This is the textbook **write skew** anomaly. Row-level locks only protect rows that are *written*. Here each transaction wrote a different `store_members` row, and the invariant spans the *set* of rows. READ COMMITTED (and even REPEATABLE READ) allows it. SERIALIZABLE would detect it, at the cost of retry logic everywhere.

## How it was detected
An invariant assertion in a concurrency test, not a "no exception" check.

## How the system recovered
Not applicable: the fix prevents the state. With `SELECT ... FROM stores WHERE id = ? FOR UPDATE` first, membership changes of one store are serialized. The second transaction waits, then counts 1 owner and is rejected.

## What we changed
Kept the store-row lock (a **materialized conflict**: a row that every competing transaction must lock). Documented in [learning/write-skew-and-row-locks.md](../learning/write-skew-and-row-locks.md). The same pattern will protect other set-level invariants (e.g. coupon usage across orders, P13).
