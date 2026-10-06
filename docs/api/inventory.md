# Inventory API (implemented, P5)

`/admin/v1/stores/{storeId}/variants/{variantId}/inventory`. Permissions: `inventory:read` (GET), `inventory:write` (others). Try it with [`http/inventory.http`](../../http/inventory.http).

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `GET .../inventory` | — | `200 {itemId, variantId, onHand, reserved, available, tracked}` (created with 0 on first access) | `variant_not_found` (404, also for other stores' variants) |
| `POST .../inventory/adjustments` | `{delta (±1,000,000, ≠0), reason: RECEIVED\|CORRECTION\|DAMAGED\|RETURNED, note?}` | `200` new level; ledger + audit entry | `stock_below_reserved` (409), `invalid_adjustment` (422) |
| `PATCH .../inventory` | `{tracked}` | `200` | — |
| `GET .../inventory/transactions?limit=&cursor=` | — | `200` ledger page (newest first): `{reason, deltaOnHand, deltaReserved, referenceType, referenceId, actorId, note, createdAt}` | — |

Reservations have no HTTP API: checkout (P7) calls the `Inventory` Java API inside its transaction. The storefront shows `available` per variant.
