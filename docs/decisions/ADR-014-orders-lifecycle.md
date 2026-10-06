# ADR-014: Order lifecycle: state machine, gapless numbering, the order owns the payment deadline

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P8

## Context
Checkout completion (P7) reserves stock and freezes prices. P8 turns it into an order that merchants operate on, before real payments exist (P10).

## Problem
1. How is an order's state modelled, and how are illegal states prevented?
2. How do stores get human-friendly order numbers (#1001, #1002, …)?
3. Reservations expire (P5) and unpaid orders must expire too. Who releases the stock?
4. How do concurrent transitions (pay vs. cancel) behave?

## Options Considered
1. One `status` enum with many values (`PENDING_PAYMENT`, `PAID_UNFULFILLED`, …): combinatorial explosion. Versus **three orthogonal dimensions** (`status`, `financial_status`, `fulfillment_status`) as Shopify does.
2. A PostgreSQL sequence per store (thousands of sequences; not transactional, so rolled-back orders leave gaps) or one global sequence (not per store). Versus **a per-store counter row** incremented with `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`.
3. Two independent timers (the reservation sweeper and an order expiry) racing to release stock. Versus **the order owns the deadline**: reservations made for an order last `payment window + grace`, the order-expiry job releases stock when the window ends, and the sweeper is only a safety net.
4. Optimistic `@Version` only, or **a pessimistic row lock (`FOR UPDATE`) for every transition**, with `@Version` as a backstop.

## Decision
- Three dimensions plus methods per transition (`markPaid`, `fulfill`, `cancel`, `expireIfOverdue`). The forbidden combinations are also CHECK constraints (`cancelled ⇒ voided ∧ unfulfilled`, `fulfilled ⇒ paid`, `total = subtotal + shipping + tax`).
- Counter row per store, starting at 1001. It is gapless and per tenant; the cost is serializing order creation per store for the duration of the placing transaction.
- Payment window 15 min (`ORDER_PAYMENT_WINDOW`), reservation grace 15 min. `UnpaidOrderExpiry` follows the ADR-011 pattern (definer function for store ids, `SKIP LOCKED` claims, a cutoff parameter for testability).
- Transitions lock the order row. Inventory side effects (commit on payment, release on cancel/expiry) run in the same transaction.
- Paid orders can't be cancelled until refunds exist (P10): 409 `cancel_requires_refund`.
- "Manual payment" (cash or bank transfer) is a real feature, and the way to pay orders until P10.
- Order lines and timeline events are append-only (UPDATE/DELETE revoked).

## Why We Chose It
- Orthogonal states make partial situations (paid but unshipped; later shipped but refunded) expressible and testable.
- The counter row is the simplest correct per-tenant numbering.
- One owner per deadline avoids races between timers.
- [EXP-007](../experiments/EXP-007-pay-vs-cancel-race.md): without the lock the outcome is still correct, *because all side effects are in the database transaction*. The lock gives precise errors and avoids wasted work, and it becomes **mandatory** once side effects are external (payment provider calls, P10).

## Trade-offs
- The per-store counter is a hot row on busy stores (orders of one store serialize on it). Fine at our scale; measure in the flash-sale test (P15). The alternative is a non-gapless sequence per shard.
- The order number is assigned before payment, so expired or cancelled orders still consume numbers (as on most platforms).

## Consequences
- P10 adds `REFUNDED`/`PARTIALLY_REFUNDED` and replaces manual payment with provider-driven transitions, keeping the state-machine methods.
- P9 emits `OrderPlaced`/`OrderPaid`/`OrderCancelled` events from these transitions via the outbox.
