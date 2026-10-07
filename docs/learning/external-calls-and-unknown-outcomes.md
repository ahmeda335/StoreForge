# Calling an External Service: Unknown Outcomes, Idempotency Keys, Compensation

**Problem.** You ask the payment provider to charge 49.00 and the call times out. Did it charge? You don't know. If you retry, the customer may pay twice. If you give up, a real payment may be lost and the customer billed for an order you cancelled. The same holds for refunds, cancellations, and any call over a network to a system you don't control.

**Why we need it.** Payments (P10), merchant webhooks (P11), and every future integration (shipping labels, tax services, search).

**Alternatives.**
- **Call inside the database transaction:** it looks atomic, but it isn't. The transaction can still roll back *after* the provider acted. Meanwhile it holds row locks and a pooled connection for the whole network call. One slow provider then exhausts the pool, and the whole store goes down with it.
- **Distributed transactions (XA / 2PC):** no payment provider supports them.
- **"Just retry":** without idempotency it's a double charge.

**Decision** ([ADR-017](../decisions/ADR-017-payments.md)). Record the intent first, call with an idempotency key, then apply the answer. Every unclear state is resolved by retrying with the same key or by asking the provider (reconciliation). Money the order can't keep is returned by a compensation that is created atomically with the discovery.

**Mental model.**
- **Three outcomes, not two:**
  - *done*;
  - *definitively not done* (declined, invalid: a 4xx);
  - ***unknown*** (read timeout, 5xx, connection reset after sending).

  Classify every failure into one of these. A connect timeout is "not done": nothing was sent. A read timeout is "unknown".
- **The idempotency key makes "unknown" safe.** Derive the key from *your* stable ids (`payment:<orderId>`, `refund:<refundId>`), never from a random value per attempt. A retry with the same key returns the first result instead of acting again. It's the same idea as our own `Idempotency-Key` in [idempotency.md](idempotency.md), seen from the client side.
- **Two short transactions around the call:** tx 1 records "about to do X" (`payment_operations IN_PROGRESS`) and commits. A crash now leaves evidence to resume from. After the call, tx 2 locks the row and applies the answer.
- **Never tell the user "failed" on "unknown".** Say "processing" (503 + Retry-After), then resolve it: the user retries, a job retries, or reconciliation asks.
- **Compensation instead of rollback.** Across systems you can't roll back. You perform the opposite action: a refund for a capture the order can't take. Create the compensation in the same transaction that discovers the need, so it can't be forgotten. (This is the core of the *saga* pattern, without the framework.)
- **A circuit breaker protects you from a provider that is down.** After many failures, stop calling for a while (fail fast). Threads aren't parked in timeouts, and the struggling provider gets no extra load. Count only "unavailable" failures: a declined card means the provider is working.
- **The provider is the source of truth for money.** If it says "succeeded" after you cancelled, believe it, and compensate.

**Project usage.** `PaymentService` (`start` → `prepare` / `createAtProvider`, `cancelForOrder`, `reconcile`, `captured`); `RefundService` (`request`, `execute`, `compensate`); `PaymentRecords.beginOperation/finishOperation`; `PaymentGateway` (circuit breaker, timer); `StripePaymentProvider.read/call` (the error classification); `PaymentReconciliation`.

**Configuration.**
- Stripe: `connect-timeout` 2s, `read-timeout` 10s.
- Circuit breaker: 50% failures over the last 20 calls (minimum 10) opens it for 30s, then 2 trial calls.
- Reconciliation: every 5 min, for anything stale for 10+ min.

**Failure cases.**
- Random keys per attempt (mutation M3 in [EXP-010](../experiments/EXP-010-payment-mutations.md): a second intent);
- a provider call inside `@Transactional`;
- treating a read timeout as "failed";
- retrying after the provider's key retention expires (Stripe: 24 h). Then *retrieve*, never re-create;
- compensating in a separate step that can be skipped by a crash;
- a reconciliation loop that dies on its first bad item ([EXP-011](../experiments/EXP-011-lost-webhooks.md)).

**Testing.** A stateful fake provider with failure injection: `UNAVAILABLE` (not performed) and `RESPONSE_LOST` (performed, answer lost). WireMock contract tests for the real adapter's error classification. Assertions on the provider side: "one intent", "refunded exactly 2000".

**Observability.**
- `storeforge_payments_provider_calls_seconds{operation, outcome=success|rejected|unavailable|unknown|circuit_open}`;
- `resilience4j_circuitbreaker_state`;
- `payment_operations` rows with status `UNKNOWN` and their `attempts`;
- `storeforge_payments_reconciled_total`, `storeforge_payments_compensations_total`.

**When NOT to use it.** Calls without side effects (a GET for display): just time out and retry. For calls whose duplicates are harmless, a plain retry is enough.
