# Orders API (implemented, P8)

`/admin/v1/stores/{storeId}/orders`. `orders:read` for GET, `orders:write` for transitions. Orders are created by storefront checkout completion ([checkout API](checkout.md)), whose response now includes `orderId`, `orderName` ("#1001") and `paymentDueAt`. Try it with [`http/orders.http`](../../http/orders.http).

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `GET /orders?status=&financialStatus=&fulfillmentStatus=&q=&limit=&cursor=` | — | `200 {data: [{id, name, number, email, total, status, financialStatus, fulfillmentStatus, itemCount, createdAt}], page}`; `q` = `#1001` / `1001` (number) or an email prefix | `invalid_parameter` |
| `GET /orders/{orderId}` | — | `200` order with `items`, `shippingAddress`, `timeline`, `paymentDueAt`, `paidAt`, `fulfilledAt`, `cancelledAt`, `cancelReason` | `order_not_found` |
| `POST /orders/{orderId}/manual-payment` | `{note?}` | `200` (PAID; reserved stock becomes sold) | `order_already_paid`, `order_not_open` (409) |
| `POST /orders/{orderId}/fulfillment` | — | `200` (FULFILLED, CLOSED) | `order_not_paid`, `order_already_fulfilled`, `order_not_open` (409) |
| `POST /orders/{orderId}/cancellation` | `{reason: customer_request\|fraud\|inventory\|other}` | `200` (CANCELLED; VOIDED if unpaid, stays REFUNDED after a full refund; reserved stock released; an open card payment is cancelled at the provider) | `cancel_requires_refund`, `order_not_open` (409) |

Payments and refunds: [payments API](payments.md). Orders now include `refunded`, and `financialStatus` can be `PARTIALLY_REFUNDED` / `REFUNDED`.

Unpaid orders are cancelled automatically when `paymentDueAt` passes (default 15 min, `ORDER_PAYMENT_WINDOW`), with `cancelReason: PAYMENT_TIMEOUT`.
