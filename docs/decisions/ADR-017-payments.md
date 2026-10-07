# ADR-017: Payments: provider calls outside transactions, synchronous order update, compensation refunds

- **Status:** Accepted
- **Date:** 2026-10-07
- **Phase:** P10
- **Builds on:** [ADR-005](ADR-005-async-processing.md) / [ADR-016](ADR-016-async-infrastructure.md) (job queue), [ADR-013](ADR-013-checkout-and-idempotency.md) (idempotency), [ADR-014](ADR-014-orders-lifecycle.md) (order state machine), [EXP-007](../experiments/EXP-007-pay-vs-cancel-race.md)

## Context
P10 moves real money through an external, unreliable system. A provider call can succeed, fail, or **time out with an unknown outcome**. Webhooks can arrive late, twice, out of order, or never. The customer can pay at the same moment the order expires. Until P10, all side effects lived inside one database transaction (EXP-007). A provider call cannot.

## Problem
1. Where does the provider call go relative to database transactions?
2. How does "the money arrived" reach the order: an event between modules, or a direct call?
3. What happens to money the order cannot take (paid after cancellation, paid twice)?
4. How do provider webhooks enter the system safely?
5. How do we test all of this without a network, and talk to Stripe without trusting a black box?

## Options Considered
1. Provider call **inside** the transaction (simple, but it holds row locks and a pooled connection for seconds and exhausts the pool when the provider slows down) vs. **outside**, with the intent recorded first.
2. `PaymentCaptured` outbox event consumed by orders (asynchronous) vs. **a direct call to the `Orders` API in the same transaction** (`MANDATORY`).
3. Refuse the late capture or "flag for a human" vs. **automatic compensation refund, created in the same transaction as the capture**.
4. Process webhooks synchronously in the HTTP request vs. **verify, store and dedup, enqueue a job, answer 200**.
5. Stripe Java SDK vs. **plain HTTP (RestClient) plus WireMock contract tests**. Mocks vs. **a stateful fake provider that speaks Stripe's wire format**.

## Decision
1. **No provider call inside a database transaction.** Every provider interaction is three steps:
   - tx 1: record the intent (`payments` row, `payment_operations` IN_PROGRESS) and commit;
   - the call, with an idempotency key derived from *our* ids (`payment:<orderId>`, `refund:<refundId>`, `cancel:<paymentId>`), behind a circuit breaker;
   - tx 2: lock the payment row and apply the answer.

   On a timeout the operation becomes `UNKNOWN`, the shopper gets **503 + Retry-After** (never "failed"), and every retry reuses the same key.
2. **Payment and order change in one transaction.** `Orders.applyPayment` locks the order *after* the payment row (fixed lock order: payment → order) and sells the stock. Both rows live in the same database, so there's no reason to accept a window where they disagree. The outbox is for effects *outside* this database (emails, merchant webhooks in P11).
3. **Compensation is automatic and atomic.** If `applyPayment` returns `NOT_PAYABLE`, the same transaction inserts a `Refund(order_not_payable)` and its job. The money can't be forgotten, because "captured" and "must be returned" commit together. Compensation refunds don't change the order's balance (it never kept the money); they appear on its timeline.
4. **Webhooks:**
   - HMAC-SHA256 over the raw body, constant-time compare, 5-minute timestamp window;
   - `provider_events (provider, event_id)` primary key for dedup;
   - a `payments.provider-event` job applies the event; all appliers are idempotent state checks.
   - **Reconciliation** (every 5 min) asks the provider about payments and refunds stale for 10+ minutes.
5. **No SDK.** We use five endpoints, and their contract (headers, form fields, and especially the classification of failures into *rejected* / *unavailable* / *outcome unknown*) is code we own and pin with WireMock. The **fake provider** is a small stateful Stripe:
   - intents, client secrets, idempotency keys, refunds;
   - signed webhooks in Stripe's format, and test cards;
   - failure injection: unavailable, or response lost after processing.

   It's the default locally and in tests. The parsing and signature code production relies on runs in every test.

## Why We Chose It
- The three-step pattern is the only one that is both pool-safe and correct under timeouts. The idempotency key turns "retry" from dangerous into safe.
- A synchronous order update removes a whole class of states ("captured but order still pending") that would otherwise need their own handling and UI.
- Automatic compensation follows the rule from ADR-014: money is never silently kept or lost.

## Trade-offs
- **Coupling:** payments calls orders directly. Acceptable inside one modular monolith with one database; the direction is one-way (orders doesn't know payments).
- **Shopper latency:** the "start payment" request makes a synchronous provider call; a slow provider makes it slow, bounded by the 10 s read timeout. The circuit breaker fails fast while the provider is down.
- **At-least-once provider calls:** the provider must deduplicate (Stripe does, for 24 h). Retries after 24 h use new keys only through reconciliation's *retrieve*, never a second create or refund.
- **Fake ≠ Stripe:** behaviour beyond the contract (3-D Secure, disputes, payouts) isn't simulated. A Stripe test-mode run is a manual step (see local-development docs).

## Consequences
- **New provider operations** follow the three-step pattern and add a `payment_operations` kind.
- **New consumers of "paid"** use `OrderPaid` (unchanged); external effects stay on the outbox.
- **Operators** watch `resilience4j_circuitbreaker_state`, `storeforge_payments_provider_calls_seconds{outcome}`, `storeforge_payments_webhooks_total{outcome="rejected"}`, `storeforge_payments_reconciled_total`, `storeforge_payments_compensations_total`. Runbook: [payments](../operations/runbooks/payments.md).
