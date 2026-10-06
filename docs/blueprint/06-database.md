# 06 — Database Design (PostgreSQL)

## Conventions

| Topic | Decision | Reason |
|---|---|---|
| Primary keys | `uuid`, generated in the application as **UUIDv7** | Non-guessable across tenants, generated without a DB round trip, time-ordered so B-tree inserts stay local (random v4 fragments indexes) |
| Human IDs | Per-store sequences for order numbers (`#1001`) | Customers read them. Never use them for authorization |
| Money | `bigint` minor units + `char(3)` currency | Floats lose cents; `numeric` is fine but slower and invites mixing scales |
| Time | `timestamptz` everywhere, stored UTC | Stores live in different timezones |
| Text identity | `citext` for emails, handles, coupon codes | Case-insensitive uniqueness enforced by the DB |
| Enums | `text` + `CHECK (status IN (...))` | Native PG enums are awkward to evolve (no drop value) |
| Naming | snake_case, plural tables, `fk_`, `uq_`, `ix_`, `ck_` prefixes | Errors name the constraint. The app maps `uq_products_store_handle` → a 409 with a field error |
| Tenant FKs | Composite `(store_id, id)` | Cross-tenant references become impossible |
| Schema | Single schema `public`. The app role is not the owner | RLS requires a non-owner role |

## Soft vs. hard deletion

- **Soft delete (status/archived_at):** products (orders reference them historically), stores (retention period), users (audit references).
- **Hard delete:** carts, expired tokens, idempotency keys after TTL, delivered jobs after retention. These have no historical value and grow without bound.
- Never soft delete with a boolean that every query must remember (`deleted = false`). That is a leakage-class bug. Prefer a **status** that is meaningful to the domain (`ARCHIVED`) plus partial unique indexes (`UNIQUE(store_id, handle) WHERE status <> 'ARCHIVED'` when handles may be reused).

## Indexes (initial; validate with `EXPLAIN ANALYZE` under realistic data)

- Every tenant table: leading `store_id` on its access-path indexes.
- `products (store_id, status, created_at DESC, id)`: admin list with keyset pagination.
- `products USING GIN (search_vector)` and `GIN (title gin_trgm_ops)`: search.
- `product_variants (store_id, sku) UNIQUE WHERE sku IS NOT NULL`.
- `orders (store_id, created_at DESC, id)`, `orders (store_id, customer_id)`, `orders (store_id, number) UNIQUE`.
- `inventory_reservations (expires_at) WHERE status = 'ACTIVE'`: partial index, tiny and hot.
- `jobs (queue, run_at) WHERE status = 'READY'`: partial index for the poller.
- `outbox_events (id) WHERE published_at IS NULL`.
- `idempotency_keys (store_id, key, scope) UNIQUE`.

## Transactions and isolation

Default isolation: **READ COMMITTED** (PostgreSQL default). Correctness for contended invariants comes from **atomic conditional updates, row locks, and constraints**, not from raising isolation globally.

| Operation | Guarantee needed | Mechanism |
|---|---|---|
| Reserve inventory | No oversell | Conditional `UPDATE ... WHERE on_hand - reserved >= :q` + CHECK constraint |
| Place order from checkout | Exactly one order per checkout, reservation committed, outbox event written | One transaction: lock checkout row (`FOR UPDATE`), insert order, commit reservations, insert outbox row, mark checkout complete. `UNIQUE(checkout_id)` as backstop |
| Payment webhook processing | Process each provider event once, monotonic state | Inbox insert `ON CONFLICT DO NOTHING` + `SELECT ... FOR UPDATE` on payment row in the same TX |
| Refund | Refunded ≤ captured | Lock payment row; CHECK constraint backstop |
| Coupon redemption | Usage ≤ limit | Conditional update |
| Owner removal | ≥1 owner | Lock store row, count owners |
| Product edit | No lost update | Optimistic `version` |
| Analytics rollup | Snapshot consistency | `REPEATABLE READ` read-only transaction |

`SERIALIZABLE` is studied (an experiment in Phase 5 compares it) but not used by default. It requires retry loops on serialization failures (`40001`) everywhere.

## Inventory: preventing oversell

Scenario: `stock = 1`. Customers A and B both buy concurrently.

**Naïve (broken):** read `available = 1` → check `1 >= 1` → `UPDATE SET on_hand = 0`. Both transactions read 1 before either writes. This is a **lost update**: both succeed and one item is sold twice.

| Approach | How | Pros | Cons |
|---|---|---|---|
| A. Pessimistic lock | `SELECT ... FOR UPDATE` then check then update | Explicit, easy to reason about | Holds lock across app round trips; contention; deadlocks if locking order varies |
| B. Optimistic lock | `UPDATE ... WHERE version = :v`; retry on 0 rows | No blocking | Retries storm under hot contention (flash sales); complex retry code |
| C. **Atomic conditional update** | `UPDATE inventory_items SET reserved = reserved + :q WHERE store_id=:s AND id=:id AND on_hand - reserved >= :q` → check affected rows (1 = ok, 0 = insufficient) | Single statement; the row lock is held only for the statement's duration within TX; no read-modify-write gap | Must be done per item. Error detail (how many are available) needs a second read |
| D. CHECK constraint alone | Let `reserved <= on_hand` fail | Last line of defense | Exceptions as control flow; aborts whole TX |
| E. Redis counter / queue | Decrement in Redis | Very high throughput | Two sources of truth; DB still needed; reconciliation |
| F. SERIALIZABLE | Let PG detect conflicts | General | Retries everywhere; harder to reason about |

**Decision ([ADR-004](../decisions/ADR-004-inventory-concurrency.md)): C + D.** Conditional atomic update as the mechanism, CHECK constraints as the safety net, and a reservation row + ledger row in the same transaction.

Multi-item carts: lock/update items in a **deterministic order (sorted by inventory_item_id)**. Two checkouts with items {X, Y} and {Y, X} otherwise acquire locks in opposite orders → **deadlock**. PostgreSQL detects it and aborts one (`40P01`), but ordering prevents it.

When to reserve: **at checkout completion (order placement)**, with a reservation that lives until payment succeeds or `reservation_expires_at` (15 min) passes. Reserving at add-to-cart lets abandoned carts lock stock. Never reserving lets payment succeed for an item that is gone.

## Race conditions, lost updates, deadlocks: where they appear

- Lost update: product edits (optimistic `version`), counters (atomic increment).
- Write skew: "≥1 owner" (two owners demote each other concurrently). Both read 2 owners and both demote. Fix: lock the store row first.
- Double insert: duplicate registration → `UNIQUE(email)` + map `23505` to a 409.
- Deadlocks: multi-row updates in inconsistent order; FK checks on parent rows (`FOR KEY SHARE`). Fix: consistent ordering, short transactions, and **never call external APIs inside a DB transaction**.

## Idempotency and retries

- `idempotency_keys(store_id, scope, key, request_hash, status, response_code, response_body, created_at, locked_until)`.
- Flow: `INSERT ... ON CONFLICT DO NOTHING`. If the insert succeeds, process and store the response. If a row exists with the same hash and a completed status, replay the stored response. If it exists with a different request hash → 422. If it exists and is in progress → 409 (retry later).
- Retry policy for DB errors: retry the **whole transaction** (not one statement) for `40001` serialization failures and `40P01` deadlocks, max 3 times with jitter. Never retry on constraint violations. Those are business answers.

## Migration strategy

- **Flyway**, versioned SQL files (`V{n}__description.sql`). SQL, not Hibernate DDL: you learn the actual schema, and `ddl-auto=validate` checks the entity mapping.
- Migrations run at application startup in dev. In production they run as a **separate step** before rollout, so a failed migration does not crash-loop all instances.
- **Expand → migrate → contract** for zero-downtime changes. For example, renaming a column: add the new column → dual-write → backfill in batches → switch reads → drop the old column in a later release.
- `CREATE INDEX CONCURRENTLY` for large tables (in a non-transactional migration).
- Never edit an applied migration. The checksum guards this.
- Two DB roles: `storeforge_owner` runs migrations (owns tables); `storeforge_app` is used at runtime (DML only, subject to RLS).
