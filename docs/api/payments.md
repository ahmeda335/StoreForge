# Payments API (implemented, P10)

Try it with [`http/payments.http`](../../http/payments.http). Design: [domains/payments.md](../domains/payments.md).

## Storefront (shopper; authorized by the cart token of the completed checkout)

| Method & path | Success | Errors |
|---|---|---|
| `POST /storefront/v1/checkouts/{checkoutId}/payment` | `200 {paymentId, provider, status, amount, clientSecret, failureCode}`. Repeating the call returns the same payment | `checkout_not_found` (404), `checkout_not_completed` (409), `order_not_awaiting_payment` / `payment_closed` (409), `payment_rejected` (422), **`payment_provider_unavailable` (503, `Retry-After: 5`)**: retrying is safe and never charges twice |
| `GET /storefront/v1/checkouts/{checkoutId}/payment` | `200`, same shape; `status` = `PENDING` \| `CAPTURED` \| `CANCELLED` \| …; `clientSecret` only while `PENDING` | `payment_not_found` (404) |

The amount is always computed from the order, server side. The client never sends an amount to pay.

## Admin (merchant)

| Method & path | Permission | Success | Errors |
|---|---|---|---|
| `GET /admin/v1/stores/{storeId}/orders/{orderId}/payment` | `orders:read` | `200 {id, provider, providerPaymentId, status, amount, captured, refunded, failureCode, refunds[], operations[{kind, status, attempts, errorCode}]}` | `order_not_found`, `payment_not_found` (404) |
| `POST /admin/v1/stores/{storeId}/orders/{orderId}/refunds` + **`Idempotency-Key`** | `orders:refund` | `201 {id, amount, reason, status: SUCCEEDED\|PENDING\|FAILED, providerRefundId}`; same key again → `200` + `Idempotent-Replayed: true` | `idempotency_key_required` (400), `invalid_amount` (400), `payment_not_found` (404), `payment_not_refundable` (409), `refund_exceeds_available` (422, counts refunds in progress), `idempotency_key_reused` (422) |

Body: `{"amount": "10.00", "reason": "customer_request|duplicate|fraud|returned|other"}`. `PENDING` means the provider hasn't confirmed yet (timeout, or a slow payment method). It completes through the retry job or a webhook. The order's `financialStatus` becomes `PARTIALLY_REFUNDED` / `REFUNDED`, and its response now includes `refunded`.

## Provider webhooks (machine to machine)

`POST /webhooks/v1/payments/{provider}` (`fake` or `stripe`, whichever is configured; any other name → 404).
- **Authentication:** the `Stripe-Signature` header (`t=…,v1=HMAC-SHA256(secret, "t.body")`) over the raw body, accepted within ±5 minutes.
- **Responses:** `200 {"received": true}` for new, duplicate and ignored events; `400 invalid_webhook` for any verification failure (the reason is logged, never returned).

## Local development only

`POST /fake-provider/v1/payment_intents/{intentId}/confirm` `{"card": "4242424242424242"}` simulates the provider's card form. `4000000000000002` is declined. The intent id is the part of `clientSecret` before `_secret_`. The endpoint exists only when `storeforge.payments.provider=fake`, and the application logs a WARN at startup.
