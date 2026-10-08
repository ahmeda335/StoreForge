# Module: shipping (P13)

**Responsibility:** a store's shipping rates and quoting them for a destination, a cart weight and a (discounted) subtotal. Shipments themselves belong to `orders`.

**Public API:** `ShippingRates` (`configured(storeId)`, `quotes(storeId, country, weightGrams, subtotalAfterDiscount)` → `Quote(rateId, name, priceMinor)`).
**Depends on:** `stores` (`StoreAccess`, `STORE_MANAGE`), `audit`, `shared`.
**Used by:** checkout.
**Decision:** [ADR-023](../decisions/ADR-023-discounts-shipping-and-order-totals.md). **API:** [api/shipping.md](../api/shipping.md).

## Rules (`RateRules`, pure)
- A rate applies when it is active, the country is in its list (empty list = everywhere), and the cart weight is in `[minWeightGrams, maxWeightGrams)` (no max = no upper bound).
- Price: `freeOver` reached by the subtotal **after discount** → 0, else the rate's price.
- Cart weight = Σ variant weight × quantity (missing weights count as 0).

## In checkout
1. The checkout view lists the quotes for its address and weight and the chosen rate (with `available`).
2. `PUT /checkouts/{id}/shipping-rate` chooses one (409 if it doesn't apply).
3. Completion: if the store has rates, a choice is required (`checkout_incomplete: [shippingRate]`), and it is quoted again (`409 shipping_rate_not_available` if it no longer applies). The order stores the rate name and amount.

## Data (V14)
`shipping_rates` (countries `text[]`, weight band, `price_minor`, `free_over_minor`, `active`; RLS), `checkouts.shipping_rate_id`, `orders.shipping_rate_name`.

## Tests
`RateRulesTest`; `ShippingIT` (quotes by destination and weight, charged total, free shipping after discount, required and re-validated choice).

## Known limitations
No carrier-calculated rates, zones as named groups, handling time or per-item rates. Inactive rates can be reactivated but not edited or deleted.
