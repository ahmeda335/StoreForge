# Module: discounts (P13)

**Responsibility:** discount codes: their rules, whether a code applies to a subtotal, redemption under usage limits, and giving a use back when an order is cancelled.

**Public API:** `Discounts` (`evaluate`, `redeem` with MANDATORY propagation), `DiscountUnavailableException`.
**Depends on:** `stores` (`StoreAccess`, `Permission.DISCOUNTS_MANAGE`), `audit`, `jobs` (event handler), `orders` (`OrderCancelled` event), `shared`.
**Used by:** checkout.
**Decision:** [ADR-023](../decisions/ADR-023-discounts-shipping-and-order-totals.md). **API:** [api/discounts.md](../api/discounts.md).

## Rules (`DiscountRules`, pure)
- **Kinds:** `PERCENTAGE` (basis points ≤ 10,000 of the subtotal, HALF_UP to the minor unit) or `FIXED_AMOUNT` (capped at the subtotal).
- **Applies when:** ACTIVE; `startsAt ≤ now < endsAt`; subtotal ≥ `minimumSubtotal`; `usageCount < usageLimit`; not already used by this customer if `oncePerCustomer`.
- **Reasons** when it doesn't: `not_found`, `disabled`, `not_started`, `expired`, `minimum_not_met`, `usage_limit_reached`, `already_used`.
- Codes are case-insensitive (`citext`; parameters cast to `citext`, see [EXP-017](../experiments/EXP-017-discount-race-and-pitfalls.md)).

## Redemption

```mermaid
sequenceDiagram
    participant C as Checkout completion (one transaction)
    participant D as DiscountService
    participant DB as PostgreSQL
    C->>D: evaluate(code, subtotal, customer)
    D-->>C: applicable + amount (else 409 discount_unavailable)
    C->>DB: place order (discount stored on it)
    C->>D: redeem(code, checkout, order, customer)
    D->>DB: UPDATE discounts SET usage_count = usage_count + 1 WHERE … AND usage_count < usage_limit
    alt 0 rows
        D-->>C: DiscountUnavailable (whole completion rolls back)
    else 1 row
        D->>DB: INSERT discount_redemptions (unique per checkout, order; per customer if once-per-customer)
    end
    Note over D,DB: OrderCancelled → DELETE redemption RETURNING → decrement only if a row was deleted
```

## Data (V13)
| Table | Notes |
|---|---|
| `discounts` | `code citext` unique per store, kind/value, minimum, window, `usage_limit`, `usage_count` (CHECK ≤ limit), `once_per_customer`, status; RLS |
| `discount_redemptions` | store, discount, checkout, order, `customer_key`; UNIQUE (store, checkout), (store, order); partial unique (discount, customer_key); RLS |
| `checkouts.discount_code` / `orders.discount_minor`, `orders.discount_code` | the order's total identity is CHECKed: `total = subtotal − discount + shipping + tax` |

## Tests
`DiscountRulesTest`; `DiscountIT`: applied and remembered on the order, concurrent last use (end-to-end and the conditional update alone), expiry during checkout, once per customer in parallel, release on cancel exactly once, rule enforcement; the admin endpoints in `TenantIsolationIT`.

## Known limitations
One code per checkout; no automatic discounts, product/collection targeting, buy-X-get-Y or free-shipping codes.
