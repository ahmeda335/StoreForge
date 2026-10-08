# Storefront cart & checkout API, admin customers (implemented, P7)

Storefront calls are anonymous. The store comes from the hostname (`X-Forwarded-Host: <handle>.storeforge.localhost` locally). Cart and checkout calls need `X-Cart-Token`. Try them with [`http/storefront.http`](../../http/storefront.http).

## Carts: `/storefront/v1/carts`

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /carts` | — | `201 {cartId, cartToken}` (`Cache-Control: no-store`; the token is shown **once**) | `store_not_found` |
| `GET /carts/{cartId}` | — | `200 {id, status, items: [{variantId, productTitle, variantTitle, quantity, unitPrice, lineTotal, sellable, inStock}], subtotal, itemCount}` (live prices) | `cart_not_found` (also for a wrong/missing token or another store) |
| `POST /carts/{cartId}/items` | `{variantId, quantity 1–100}` | `200` cart (same variant again → quantity added) | `item_unavailable`, `quantity_limit`, `cart_full` (422); `cart_closed` (409) |
| `PATCH /carts/{cartId}/items/{variantId}` | `{quantity 0–100}` (0 removes) | `200` cart | as above |
| `DELETE /carts/{cartId}/items/{variantId}` | — | `200` cart | — |

## Checkouts: `/storefront/v1/checkouts`

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /checkouts` | `{cartId, email?, shippingAddress?}` | `201` new / `200` existing OPEN checkout of this cart | `cart_empty` (422), `cart_closed` (409), `invalid_country` |
| `GET /checkouts/{id}` | — | `200 {id, status, email, shippingAddress, lines, unavailableItems, subtotal, discount, shippingRates, shippingRate, shipping, tax, total, customerId, completedAt, paymentDueAt, orderId, orderName}`; OPEN = live prices, COMPLETED = frozen snapshot | `checkout_not_found` |
| `PATCH /checkouts/{id}` | `{email?, shippingAddress?}` | `200` | `checkout_not_open` (409) |
| `POST /checkouts/{id}/completion` | `{expectedTotal}` + header **`Idempotency-Key`** | `200` COMPLETED checkout with `orderId`/`orderName` (the order is placed in the same transaction, P8); header `Idempotent-Replayed: true\|false` | `idempotency_key_required` (400); `idempotency_key_reused` (422); `total_changed` (409, detail has the new total); `insufficient_stock`, `item_unavailable`, `checkout_incomplete`, `invalid_amount` (422); `checkout_not_open` (409) |

`shippingAddress`: `{name, line1, line2?, city, region?, postalCode, country}` (`country` = ISO 3166-1 alpha-2).

**Client contract for completion:** generate one UUID per "Place order" intent and reuse it for every retry of *that* attempt. Send the total you displayed. On `total_changed`, show the new total and let the shopper confirm with a **new** key.

## Discounts and shipping (P13)

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `PUT /checkouts/{id}/discount` | `{code}` | `200` checkout with `discount: {code, amount, applicable, reason}` | `discount_not_applicable` (422, detail has the reason), `checkout_not_open` (409) |
| `DELETE /checkouts/{id}/discount` | — | `200` checkout | `checkout_not_open` (409) |
| `PUT /checkouts/{id}/shipping-rate` | `{rateId}` | `200` checkout with `shippingRate: {id, name, price, available}` | `shipping_rate_not_available` (409), `checkout_incomplete` (422, address first) |

- `shippingRates` lists what applies to the current address and cart weight; `discount` is evaluated live on an open checkout (a code can stop applying: `applicable: false` + `reason`, and it isn't deducted).
- `total = subtotal − discount + shipping + tax`.
- **Completion** additionally fails with `discount_unavailable` (409: expired or used up meanwhile), `shipping_rate_not_available` (409), or `checkout_incomplete` with `shippingRate` (422) when the store has rates and none is chosen. `expectedTotal` still guards everything else.

## Admin: `/admin/v1/stores/{storeId}/customers` (`customers:read`)

| Method & path | Success |
|---|---|
| `GET /customers?limit=&cursor=` | `200 {data: [{id, email, firstName, lastName, createdAt}], page}` |
| `GET /customers/{customerId}` | `200` customer, or `customer_not_found` |
