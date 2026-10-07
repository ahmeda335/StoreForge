# EXP-010: Do the payment tests guard the money? (mutations)

- **Date:** 2026-10-07 · **Phase:** P10 · **Setup:** Testcontainers PostgreSQL, fake provider; `RefundIT`, `CardPaymentIT`, `PaymentRecoveryIT`.

## Experiment
Break one safety mechanism at a time and run the test meant to guard it:

| # | Mutation | Guarding test |
|---|---|---|
| M1 | Refund request reads the payment without `FOR UPDATE` | `concurrentRefundsNeverExceedTheCapturedAmount` |
| M2 | `capture()` result ignored (capture applied again) | `duplicateAndConcurrentWebhookDeliveriesAreAppliedOnce` |
| M3 | Random idempotency key per creation attempt | `aLostProviderResponseIsRetriedSafelyWithTheSameKey` |
| M4 | No compensation refund when the order can't take the money | `moneyCapturedForAnExpiredOrderIsRefundedAutomatically` |

## Expected behavior
Every mutation fails its test.

## Actual behavior
| # | Result |
|---|---|
| M1 | **Failed:** 10 refunds accepted (expected 4). The excess was only stopped by the provider refusing to refund more than captured |
| M2 | **Survived** at first. Fixed the test (see below); now **fails**: the order timeline gets an extra REFUNDED, and the legitimate payment is refunded |
| M3 | **Failed:** the retry created a second intent at the provider |
| M4 | **Failed:** the payment stayed CAPTURED for a cancelled order, and nothing was refunded |

## What failed / why
**M2 surviving was a test gap.** The duplicate-webhook test only redelivered the *same* event. Those duplicates are stopped earlier, by the `provider_events` primary key, so the capture's own state check was never exercised.

The same success can reach us under a *different* event id: a resent event, or reconciliation racing the webhook. Without the state check, it would be treated as a payment for an already-paid order, and **the customer's legitimate payment would be refunded automatically**.

## How it was detected
Mutation testing: the test stayed green with the mechanism removed.

## How the system recovered
N/A. Mutations reverted.

## What we changed
- `CardPaymentIT` now also delivers the same success re-signed under a new event id, and asserts that there's no refund. Verified that it fails under M2.
- Lesson recorded in [learning/inbound-webhooks.md](../learning/inbound-webhooks.md): event-id dedup is not enough; the applier must be idempotent too, and each layer of defense needs its own test.
