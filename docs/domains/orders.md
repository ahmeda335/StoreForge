# Module: orders (P8)

**Responsibility:** the record of a sale after checkout completion: payment state, fulfillment, cancellation, expiry, history.

**Public API** (`dev.storeforge.orders`): `Orders.place(storeId, OrderPlacement)` (`MANDATORY` transaction, called by checkout completion) and `Orders.reservationHold()` (how long checkout must reserve stock).
**Depends on:** `shared`, `stores` (`StoreAccess`), `inventory` (commit/release), `audit`, `identity`. **Used by:** checkout.

## State machine ([ADR-014](../decisions/ADR-014-orders-lifecycle.md))

```mermaid
stateDiagram-v2
    state "OPEN / PENDING / UNFULFILLED" as New
    state "OPEN / PAID / UNFULFILLED" as Paid
    state "CLOSED / PAID / FULFILLED" as Done
    state "CANCELLED / VOIDED / UNFULFILLED" as Cancelled
    [*] --> New: checkout completion (stock reserved)
    New --> Paid: manual-payment (stock committed: sold)
    New --> Cancelled: cancellation (stock released)
    New --> Cancelled: payment window passed (expiry job, reason PAYMENT_TIMEOUT)
    Paid --> Done: fulfillment
    Paid --> Paid: cancellation → 409 cancel_requires_refund (refunds: P10)
```

## Data (V7)

| Table | Notes |
|---|---|
| `orders` | `UNIQUE (store_id, number)`, `UNIQUE (store_id, checkout_id)` (one order per checkout); CHECKs for totals and forbidden state combinations; partial index on `payment_due_at` for unpaid open orders |
| `order_items` | Immutable snapshot (UPDATE/DELETE revoked); `line_total = unit × qty` CHECK |
| `order_events` | Append-only timeline: PLACED, PAID, FULFILLED, CANCELLED, EXPIRED |
| `store_counters` | Next order number per store (gapless, transactional) |
| `checkouts.order_id` | Link from checkout to its order |

## Flows
- **Placement:** inside the completion transaction: `nextOrderNumber` (counter row UPDATE, locked until commit) → `orders` row → `order_items` → `PLACED` event. A failure anywhere (e.g. insufficient stock) rolls everything back, including the number.
- **Transition:** tenant transaction → `orders:write` → `SELECT … FOR UPDATE` → state-machine method → inventory commit/release → timeline event + audit → commit.
- **Expiry:** `UnpaidOrderExpiry` (every 30s) → `stores_with_overdue_orders(cutoff)` → per store: `SKIP LOCKED` claim → `expireIfOverdue` → release stock → `EXPIRED` event.

## Tests
`OrderStateMachineTest` (every legal and illegal transition, pure unit), `OrderPlacementIT` (snapshot, per-store numbering, **12 concurrent orders → #1001..#1012**, immutable lines), `OrderLifecycleIT` (pay → sell → fulfil, cancel releases, illegal transitions, **pay vs. cancel race ×5**, expiry, list filters/search, read-only role); orders endpoints in `TenantIsolationIT`.

## Known limitations
- No payments (P10): money is recorded only via manual payment. No refunds, so paid orders can't be cancelled.
- No partial fulfillment or shipments with tracking (P13).
- No events to other systems yet (P9 outbox → emails, webhooks).
- Customers can't look up their order yet (no order status page or customer accounts).
