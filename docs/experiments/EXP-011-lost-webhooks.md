# EXP-011: Webhooks never arrive (and the reconciliation run that died on one bad payment)

- **Date:** 2026-10-07 · **Phase:** P10
- **Setup:** app from the jar with the fake provider and real HTTP webhook delivery; Compose PostgreSQL.
  - `FAKE_PROVIDER_WEBHOOK_URL=http://localhost:9/...` (a closed port: every delivery fails);
  - `PAYMENTS_RECONCILIATION_INTERVAL=10s`, `PAYMENTS_STALE_AFTER=20s`.

## Experiment
1. Baseline, with delivery working:
   - order, payment, card 4242;
   - refund, then the same refund key again, then an over-refund;
   - a declined card.
2. With delivery broken: order, payment, card 4242. Then poll the storefront payment status without touching anything.

## Expected behavior
1. The webhook arrives asynchronously, and the order is PAID within about a second. The refund is idempotent, the over-refund is refused, and the decline is recorded.
2. The order stays PENDING until reconciliation notices the stale payment (after 20 s + up to 10 s), asks the provider, and applies the capture. A WARN log says so.

## Actual behavior
| Step | Observed |
|---|---|
| 1 | Captured via webhook **0.46 s** after confirming; order `#1001` PAID; refund `201 SUCCEEDED`; same key `200 Idempotent-Replayed: true`; 40.00 → `422 refund_exceeds_available (39.00, including refunds in progress)`; decline → `PENDING` + `card_declined` after 0.25 s. Metrics: `webhooks{accepted} 3`, `provider_calls{create_payment,success} 2`, `refunds{merchant,succeeded} 1`, circuit `closed` |
| 2 | Order paid **38 s** after the customer paid, with **no webhook**: WARN "Reconciliation found a captured payment whose webhook never arrived; applied it", `storeforge_payments_reconciled_total 1` |
| 2 (bug) | **Two earlier reconciliation runs crashed**: `ERROR Unexpected error occurred in scheduled task`, plus `provider_calls{retrieve_payment, outcome=rejected} 2` |

## What failed / why
The database still held PENDING payments from earlier runs. Their intents existed only in the previous app's in-memory fake provider, so retrieving them failed with `resource_missing`, which is a *rejection*. Reconciliation caught only *unavailable* errors. The first such payment threw out of the loop and **aborted the whole run, for every store**.

Recovery happened anyway, but only by luck: each attempt "touched" the bad payments, so a later run skipped them for 20 s.

With Stripe, this happens for any payment the account doesn't know: a payment from another environment, a deleted object, a key rotated to another account. **One such payment would silently stop lost-payment recovery for the whole platform.**

## How it was detected
Reading the WARN/ERROR lines after the experiment. The success path alone looked fine.

## How the system recovered
The lost payment was recovered, but only because of the touch side effect.

## What we changed
- `PaymentReconciliation` isolates each item:
  - unavailable providers are counted;
  - any other error is logged at WARN with the payment or refund id, counted in `storeforge_payments_reconciliation_failures_total{reason}`, and the run continues.
- Regression test `PaymentRecoveryIT.onePoisonedPaymentDoesNotStopReconciliationOfTheOthers`. Verified that it fails on the old code with the same `resource_missing` error.
- The general rule ("batch jobs isolate items") is recorded in [learning/external-calls-and-unknown-outcomes.md](../learning/external-calls-and-unknown-outcomes.md).
