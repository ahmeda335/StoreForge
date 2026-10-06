# Module: inventory (P5)

**Responsibility:** physical stock per variant (`on_hand`), stock promised to checkouts (`reserved`), the reservation lifecycle, and an append-only ledger explaining every change.

**Public API** (`dev.storeforge.inventory`): `Inventory` (`reserve`, `commit`, `release`, `availability`), `ReservationLine`, `InsufficientStockException`. All methods are `Propagation.MANDATORY`: they join the caller's tenant transaction, so a reservation commits or rolls back with the order that needs it.

**Depends on:** `shared`, `stores`, `audit`. It has no code dependency on catalog: the link to variants is a composite foreign key. **Used by:** catalog (storefront availability), checkout/orders (P7/P8).

## Model

```text
available = on_hand − reserved            (CHECK: on_hand ≥ 0, 0 ≤ reserved ≤ on_hand)

reserve ──► ACTIVE ──commit──►  COMMITTED   on_hand −= q, reserved −= q   (ledger: SOLD)
              │    ──release──► RELEASED    reserved −= q                 (ledger: RELEASED)
              └────(sweeper)──► EXPIRED     reserved −= q                 (ledger: EXPIRED)
adjustment (RECEIVED/CORRECTION/DAMAGED/RETURNED): on_hand += delta, never below reserved
```

| Table | Notes |
|---|---|
| `inventory_items` | One per variant, created lazily (`INSERT ... ON CONFLICT DO NOTHING`); `tracked=false` means always available, never counted |
| `inventory_reservations` | `UNIQUE (store_id, checkout_id, inventory_item_id)` makes reserving idempotent; partial index on `expires_at WHERE status='ACTIVE'` |
| `inventory_transactions` | Ledger, append-only (UPDATE/DELETE revoked); `SUM(deltas) = levels` (reconciliation) |

## Concurrency design ([ADR-004](../decisions/ADR-004-inventory-concurrency.md), [EXP-004](../experiments/EXP-004-oversell-three-ways.md))

| Mechanism | Prevents |
|---|---|
| `UPDATE ... SET reserved = reserved + q WHERE on_hand - reserved >= q` (one statement) | Oversell / lost update: no gap between check and write |
| CHECK `reserved <= on_hand` | Any code path (bug, manual SQL) committing an oversold state |
| Lines merged and processed in ascending variant id order | Deadlocks between multi-item checkouts |
| `status = 'ACTIVE'` condition on every transition | Double commit/release, sweeper vs. checkout races |
| `FOR UPDATE SKIP LOCKED` batches in the sweeper | Two sweepers (two instances) processing or blocking on the same rows |
| Throwing on the first short line inside the transaction | Partial reservations (all-or-nothing) |

## Expiry sweeper ([ADR-011](../decisions/ADR-011-cross-tenant-background-work.md))

```mermaid
sequenceDiagram
    participant S as ReservationSweeper (@Scheduled, every 30s)
    participant DB as PostgreSQL
    S->>DB: SELECT store_id FROM stores_with_expired_reservations(100)   -- definer fn: ids only
    loop each store
        S->>DB: BEGIN; set_config(app.current_store_id)
        S->>DB: WITH expired AS (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 500) UPDATE ... SET status='EXPIRED' RETURNING
        S->>DB: UPDATE inventory_items SET reserved = reserved - q (sorted by item)
        S->>DB: INSERT inventory_transactions (EXPIRED)
        S->>DB: COMMIT
    end
```

## Tests
`InventoryAdminIT` (adjust + ledger, below-reserved 409, reason validation, foreign/unknown variant 404, storefront availability incl. untracked), `InventoryReservationIT` (commit/release, all-or-nothing, idempotency, **3 concurrent sweepers expire each reservation once**, untracked), `InventoryConcurrencyIT` (**100 buyers / 1 unit ×3**, 80 / 50, opposite lock orders, ledger reconciliation), `OversellExperimentIT` (EXP-004).

## Known limitations
- Nothing calls `reserve` over HTTP yet. Checkout (P7) does.
- A single location per variant (no multi-warehouse).
- Variant deletion cascades its inventory history. Orders (P8) will prevent deleting sold variants.
- Hot-item throughput is bounded by row-lock serialization on one row. It gets measured in the flash-sale load test (P15).
