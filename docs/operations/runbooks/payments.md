# Runbook: Payments

## Symptoms
- **Shoppers get `503 payment_provider_unavailable`:**
  - `storeforge_payments_provider_calls_seconds_count{outcome=~"unavailable|unknown|circuit_open"}` rising;
  - `resilience4j_circuitbreaker_state{name="payment-provider",state="open"} 1`.
- **Orders paid at the provider but PENDING here:** `storeforge_payments_reconciled_total` increasing means webhooks are being lost.
- **Webhooks rejected:** `storeforge_payments_webhooks_total{outcome="rejected"}` rising, with WARN "Rejected a payment webhook".
- **Refunds stuck in PENDING:** DEAD `payments.refund` jobs (see the [dead-jobs runbook](dead-jobs.md)).
- **Unexpected automatic refunds:** `storeforge_payments_compensations_total` rising, with WARN "Payment captured for an order that can no longer be paid".

## Diagnose
1. **Provider down?** Check the provider's status page, then the call outcomes by `operation`. `circuit_open` means we're failing fast on purpose: the circuit retries by itself after 30 s.
2. **Timeouts (`unknown`)?** Look at `payment_operations` with `status='UNKNOWN'` and their `attempts` (merchant view: `GET …/orders/{id}/payment`). They resolve by retry with the same key, so nothing is charged twice.
3. **Rejected webhooks:**
   - `reason=signature mismatch` → wrong `STRIPE_WEBHOOK_SECRET` (rotated in the dashboard?) or an attack;
   - `timestamp outside tolerance` → clock skew on our hosts, or replays.
4. **Lost webhooks:** in the provider dashboard, check the webhook endpoint's delivery attempts and errors. Reconciliation keeps orders correct meanwhile, with 10–15 min of latency.
5. **Compensations:** a few are normal (shoppers paying at the last second of the payment window, or merchants marking cash payments on orders that were then paid by card). A spike means the payment window is too short, or a bug cancels orders.
6. **Reconciliation errors:** WARN "Reconciliation of one item failed" with the `paymentId`. Look the payment up at the provider. `resource_missing` means a payment from another account or environment.

## Act
- **Provider outage:** nothing to do. Shoppers see "retry", reconciliation catches up afterwards. Consider extending the payment window (`ORDER_PAYMENT_WINDOW`) for a long outage, so orders don't expire while customers can't pay.
- **Wrong webhook secret:** fix it in the secret store and restart. Lost events are recovered by reconciliation, or re-sent from the dashboard (event-id dedup makes that safe).
- **DEAD refund jobs:** fix the cause, then requeue (`POST :8081/actuator/jobs/{id}`). The provider key `refund:<refundId>` prevents double refunds.
- **Never** "fix" a payment by editing database rows: money state must come from the provider (retrieve), or from a refund through the API.

## Notes
- **Card data is never in our systems.** A request to look up a card number is answered "we can't, by design".
- Stripe keeps idempotency keys for 24 h. After that, retries use *retrieve* (reconciliation), never a new create or refund.
