# ADR-023: Discounts and shipping are priced in checkout, re-validated at completion, frozen on the order

- **Status:** Accepted
- **Date:** 2026-10-08
- **Phase:** P13
- **Evidence:** [EXP-017](../experiments/EXP-017-discount-race-and-pitfalls.md); `DiscountIT`, `ShippingIT`, `ShipmentIT`

## Context
P7 priced a checkout as `subtotal + shipping (0) + tax (0)`. P13 adds discount codes, shipping rates and shipments. They must agree on one total, survive concurrency (the "last coupon" race, two warehouse workers shipping the same units), and must never silently change what the customer pays.

## Problem
1. In which order are the amounts computed, and where is the result stored?
2. How is a usage limit enforced when many checkouts complete at once?
3. What happens when a code or rate stops applying between "shown" and "paid"?
4. How does fulfilment work when an order ships in parts?

## Options Considered
1. **Pricing:** a pricing engine module with stackable rules; **a fixed pipeline in checkout** (subtotal → one discount → shipping on the discounted subtotal → tax).
2. **Usage limits:** count redemptions (`SELECT count(*)`) then insert; a row lock on the discount; **a conditional `UPDATE … SET usage_count = usage_count + 1 WHERE usage_count < usage_limit`** with a CHECK as backstop.
3. **Stale choices:** silently drop the code / switch the rate; **refuse completion with a specific 409** and let the client re-show the price.
4. **Fulfilment:** a FULFILLED flag (P8); **shipments** with lines, tracked against what was ordered under the order row lock.

## Decision
1. **One pipeline, in checkout:** `total = subtotal − discount + shipping + tax`.
   - One code per checkout, no stacking. Percentages HALF_UP to the minor unit; fixed amounts capped at the subtotal.
   - Shipping rates match by country (empty = all) and half-open weight band `[min, max)`; free shipping is judged on the subtotal **after** the discount.
   - The order stores every component (`discount_minor`, `discount_code`, `shipping_minor`, `shipping_rate_name`), and the database CHECKs the identity.
2. **Conditional update** for usage limits; once-per-customer is a partial unique index on redemptions. Redemption happens in the completion transaction (MANDATORY propagation). A cancelled order gives its use back (event consumer, idempotent: delete the redemption, then decrement).
3. **Re-validate at completion:** `409 discount_unavailable` / `409 shipping_rate_not_available`, and the existing `expectedTotal` guard (ADR-013) catches any other drift. A store with rates requires a choice (`checkout_incomplete: shippingRate`); stores without rates keep shipping 0.
4. **Shipments:** lines (or "everything remaining") with carrier and tracking, under the order row lock; `UNFULFILLED → PARTIALLY_FULFILLED → FULFILLED` (closes the order). Cancelling an in-transit shipment returns its quantities and reopens the order. Orders with shipments can't be cancelled. P8's `/fulfillment` ships everything remaining.

## Why We Chose It
- A fixed pipeline is what merchants can predict and tests can pin down; stacking rules are a product decision we don't need yet.
- The conditional update is one statement, needs no lock held across code, and is what EXP-004 proved for stock.
- A customer must never pay a different amount than the one shown. Refusing is honest; the client re-reads the checkout.

## Trade-offs
- No stacking, no automatic discounts, no per-product or per-collection rules.
- Shipping is a flat price per band, not carrier-calculated; no per-shipment rate.
- Free shipping after discount can surprise customers ("I had 60 in my cart"). It is the common convention and documented.

## Consequences
- Checkout completions in one store are already serialized by the order-number counter lock (ADR-014). The conditional update is therefore **defence in depth** today; it becomes the only guard if order numbering ever stops serializing. A direct test covers it alone (EXP-017).
- Tax is still 0: the pipeline has its slot.
