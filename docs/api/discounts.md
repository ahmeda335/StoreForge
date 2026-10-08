# Discounts API (implemented, P13)

Design: [domains/discounts.md](../domains/discounts.md). Applying a code to a checkout: [checkout API](checkout.md#discounts-and-shipping-p13).

## Admin: `/admin/v1/stores/{storeId}/discounts` (`discounts:manage`)

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /` | `{code (3–40: letters, digits, _ -), kind: PERCENTAGE\|FIXED_AMOUNT, value ("10" = 10 % or 10.00), minimumSubtotal?, startsAt?, endsAt?, usageLimit?, oncePerCustomer?}` | `201 {id, code, kind, value, minimumSubtotal, startsAt, endsAt, usageLimit, usageCount, oncePerCustomer, status, createdAt}` | `validation_failed` (422), `discount_code_taken` (409) |
| `GET /` | — | `200 {data: [discount]}` | — |
| `PATCH /{discountId}` | `{status?: ACTIVE\|DISABLED, endsAt?, usageLimit?, clearEndsAt?, clearUsageLimit?}` | `200` discount | `discount_not_found` |

Codes are case-insensitive. `usageCount` counts redemptions by placed orders; a cancelled order gives its use back.
