# ADR-013: Checkout scope, server-side pricing guard, and transactional idempotency

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P7

## Context
Checkout is the first customer-driven write flow: anonymous shoppers, money, stock, and requests that clients retry (timeouts, double clicks, mobile networks).

## Problem
1. How much of "customers" and "orders" belongs in P7?
2. How do anonymous shoppers prove a cart is theirs?
3. How do we avoid charging (later) an amount the customer didn't see?
4. How do we make completion safe to retry?

## Options Considered
1. Full storefront customer accounts (login, a second token audience) now, vs. **guest customers keyed by email per store**. Creating the order in P7 vs. **completion = reservation + frozen snapshot, with the order joining the same transaction in P8**.
2. Session cookie vs. **a random cart token returned once, sent as `X-Cart-Token`, stored hashed**.
3. Accept whatever the server computes at completion, or **the client sends `expectedTotal` (what it displayed); a mismatch gives 409 `total_changed`**.
4. Idempotency models:
   - (a) **Single transaction:** claim row + work + stored response, all in one transaction. Duplicates block on the key's unique index and then replay.
   - (b) **IN_PROGRESS row committed first**, work, then the response stored. Duplicates get 409 while in progress, and crashed claims need lock expiry.
   - (c) **No key**: rely on the state machine alone (only the first succeeds; retries get 409).

## Decision
- Guest customers (`Customers.findOrCreate`, race-free upsert). Customer accounts come later, if ever needed for learning.
- Completion reserves stock (15 min), records the customer, freezes `checkout_lines` and totals, and converts the cart. P8 adds order creation **inside the same transaction**.
- Cart tokens as bearer secrets: SHA-256 at rest, never logged (redacted `toString`), `Cache-Control: no-store` on the response that reveals them. A wrong or missing token returns 404, the same answer as an unknown id.
- `expectedTotal` guard on completion.
- **Idempotency model (a)** for P7, with a required `Idempotency-Key` header on completion. Keys are scoped per store and operation. The **request fingerprint includes the cart-token hash**, so a key can't be used to read another shopper's stored response (which contains PII). Failures roll back and leave no record. The response carries `Idempotent-Replayed: true|false`.

## Why We Chose It
- Model (a) has no IN_PROGRESS state and no crash-recovery timer, and it is correct by construction while the work is purely database work.
- Model (c) alone keeps the *effect* correct (row lock + status check), but gives retries a different answer (409). Verified by mutation in [EXP-006](../experiments/EXP-006-duplicate-checkout-completion.md). Clients need the *same* answer.
- `expectedTotal` turns a silent price race into an explicit, testable conflict.

## Trade-offs
- Model (a) holds a transaction (and makes duplicates wait) for as long as the work runs. **Payments (P10) involve slow external calls outside the transaction, so they must use model (b).** That is a documented upgrade path, not a redesign.
- Error responses aren't stored. A retry re-executes, and can succeed if conditions changed (tested). Stripe stores errors too; we chose re-execution because our failures are deterministic validations.
- Old idempotency records accumulate. A cleanup job (24h retention) arrives with the job queue (P9).
- Guest customers can't see their order history yet.

## Consequences
- Every endpoint that creates money-relevant state requires `Idempotency-Key` (refunds P10, captures P10).
- Every new idempotent endpoint must include the caller's identity in the fingerprint.
