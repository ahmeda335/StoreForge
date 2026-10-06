# Write Skew and Row Locks

**Problem.** Some invariants span *several rows*: "a store keeps at least one owner", "coupon used ≤ 100 times across all orders", "a doctor on call". Two concurrent transactions can each read a valid state, each change a *different* row, and together break the invariant. Neither waits for the other, because they never touch the same row.

**Why we need it.** The last-owner rule (P3). Later, every "count across rows then decide" rule (discount limits P13, shipment quantities P8).

**Alternatives.**
| Approach | How | Cost |
|---|---|---|
| SERIALIZABLE isolation | PostgreSQL detects the dangerous read/write dependency and aborts one transaction (40001) | Every caller needs retry logic; aborts under contention |
| **Materialize the conflict** | Both transactions lock one shared row first (`SELECT ... FROM stores WHERE id = ? FOR UPDATE`) | Serializes changes per store (fine: membership changes are rare) |
| Constraint / trigger | Deferred constraint trigger counting owners | Logic hidden in the DB, harder to test and evolve |
| Counter column + CHECK | `owner_count` with `CHECK (owner_count >= 1)` and atomic updates | Denormalization to keep in sync |

**Decision.** Lock the parent (store) row before reading the set ([EXP-003](../experiments/EXP-003-write-skew-last-owner.md)).

**Mental model.**
- READ COMMITTED: each *statement* sees data committed before it started. Row locks are taken by UPDATE/DELETE/`FOR UPDATE` on the **rows they touch**.
- Lost update (same row, read-modify-write) is fixed by atomic updates or locks on that row. **Write skew** (different rows, shared invariant) needs a lock on something they *both* touch, or SERIALIZABLE.
- `FOR UPDATE` on the store row: the second transaction blocks until the first commits, then reads the **new** state (one owner left) and refuses.
- Keep such locks short, and always take them in the same order (store → member) to avoid deadlocks.

**Project usage.** `MemberStore.lockStore` in `MemberService.changeRole` / `remove`.

**Failure cases.** Forgetting the lock in a new code path that changes roles (the concurrency test only covers the paths it calls). Holding the lock while doing slow work (no network calls inside!). Deadlocks if another path locks member rows first and then the store.

**Testing.** `StaffManagementIT.concurrentMutualDemotionNeverLeavesTheStoreWithoutAnOwner`. Mutation-tested: without the lock it fails every time.

**Observability.** Lock waits will show as long JDBC spans (P6). `pg_stat_activity.wait_event_type = 'Lock'`.

**When NOT to use it.** High-contention hot paths, where serializing on one row would become the bottleneck (e.g. flash-sale inventory). There, prefer single-statement conditional updates on the contended row itself (ADR-004).
