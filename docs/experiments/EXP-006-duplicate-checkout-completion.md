# EXP-006: Duplicate checkout completion with and without stored idempotency

- **Date:** 2026-10-06 · **Phase:** P7 · **Setup:** `CheckoutIdempotencyIT`; one checkout for 3 units; 20 concurrent `POST …/completion` requests with the **same** `Idempotency-Key`; Hikari pool 10.

## Experiment
(a) Production code. (b) Mutation: `Idempotency.execute` skips the key table and just runs the work.

## Expected behavior
- (a) 20 × `200` with byte-identical bodies; exactly one `Idempotent-Replayed: false`; 3 units reserved.
- (b) Stock is still reserved once: the checkout row lock (`FOR UPDATE`) and the `status = 'OPEN'` check serialize completions. But 19 requests get `409 checkout_not_open`.

## Actual behavior
| Variant | Result |
|---|---|
| (a) | As expected (every run) |
| (b) | `twentyConcurrentRetries…` failed: non-200 responses; `aKeyCannotBeReused…` failed: a key reused with a different payload was not rejected |

The manual run against the real app (Compose DB) confirmed (a) over HTTP: completion #1 `200 replayed=false`, an identical retry `200 replayed=true`, stock reserved 2 of 4, and one customer.

## What failed / why
Nothing in production code. The experiment separates two properties that are easy to conflate:
- **Effect idempotency** (do it at most once) comes from the state machine + row lock.
- **Response idempotency** (every retry gets the original answer) needs the stored result.

A client that times out on a successful completion and retries without stored results is told "checkout_not_open". It would show the shopper an error for a purchase that actually went through, and the shopper might buy again.

## How it was detected
Assertions on response codes and bodies of all 20 concurrent requests.

## How the system recovered
N/A.

## What we changed
Kept stored idempotency results; documented both properties in [ADR-013](../decisions/ADR-013-checkout-and-idempotency.md) and [learning/idempotency.md](../learning/idempotency.md).
