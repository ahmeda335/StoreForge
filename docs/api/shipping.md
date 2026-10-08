# Shipping API (implemented, P13)

Design: [domains/shipping.md](../domains/shipping.md). Choosing a rate: [checkout API](checkout.md#discounts-and-shipping-p13). Shipments: [orders API](orders.md#shipments-p13).

## Admin: `/admin/v1/stores/{storeId}/shipping-rates` (`store:manage`)

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /` | `{name, countries? (ISO 2-letter; empty/omitted = everywhere), minWeightGrams? (default 0), maxWeightGrams? (exclusive), price, freeOver?}` | `201 {id, name, countries, minWeightGrams, maxWeightGrams, price, freeOver, active}` | `validation_failed` (422) |
| `GET /` | — | `200 {data: [rate]}` | — |
| `PATCH /{rateId}` | `{active}` | `200` rate | `shipping_rate_not_found` |

`freeOver` is compared with the subtotal **after** discounts. A store without any rate charges no shipping and doesn't ask for a choice.
