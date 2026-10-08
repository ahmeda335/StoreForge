# Discounts, Shipping and the Pricing Pipeline

**Problem.** Once more than one thing changes the price (codes, shipping, later tax), the order of operations decides the amount, and a limited code ("first 100 customers") must not be used 101 times.

**Why we need it.** Discount codes and shipping rates (P13).

**Alternatives.**
- **Rule engine with stacking and priorities:** flexible, hard to predict and test.
- **Fixed pipeline:** one code, a defined order. Predictable.
- **Usage limits:** count-then-insert (races), a row lock on the discount (serializes), a conditional update (one atomic statement).

**Decision.** `total = subtotal − discount + shipping + tax`; conditional update for limits; re-validate at completion ([ADR-023](../decisions/ADR-023-discounts-shipping-and-order-totals.md)).

**Mental model.**
- **Money in minor units, rounding once, explicitly:** percentage of the subtotal in basis points, HALF_UP to the cent. Fixed amounts are capped at the subtotal (a total is never negative).
- **Order matters:** free shipping "over 50" judged after the discount means a 60 cart with 20% off pays shipping. Pick one convention and document it.
- **Show vs. charge:** an open checkout evaluates the code live and explains why it doesn't apply (`expired`, `minimum_not_met`, `usage_limit_reached`…). Completion re-checks and **refuses** with a specific 409 rather than charging a different amount.
- **Limits are write-time invariants:** `UPDATE discounts SET usage_count = usage_count + 1 WHERE id = ? AND usage_count < usage_limit` returns 0 rows when the code is used up. A CHECK `usage_count <= usage_limit` is the backstop. "Once per customer" is a partial unique index on redemptions.
- **Give back on cancel,** idempotently: delete the redemption row and decrement only if a row was deleted.
- **Freeze on the order:** the order stores discount code and amount, shipping name and amount; later edits to the code or rate don't change history.
- **Partial fulfilment** is the same pattern for quantities: remaining = ordered − shipped (non-cancelled shipments), checked under the order row lock.

**Project usage.** `discounts` module (`DiscountRules`, `DiscountStore`, `DiscountService`), `shipping` module (`RateRules`, `ShippingRateService`), checkout completion, `ShipmentService`.

**Failure cases.** Rounding per line vs. per order (cents drift); percent of the total incl. shipping; count-then-insert; a race test that never races because another lock serializes the flow ([EXP-017](../experiments/EXP-017-discount-race-and-pitfalls.md)); `citext = text` being case-sensitive.

**Testing.** `DiscountRulesTest`, `RateRulesTest` (pure), `DiscountIT` (race, direct conditional update, expiry during checkout, once per customer in parallel, release on cancel), `ShippingIT`, `ShipmentIT` (concurrent shipments, mutation-checked).

**Observability.** Order timeline (SHIPPED, FULFILLED), audit entries for discount and rate changes, `fulfillments/create` webhooks.

**When NOT to use it.** Marketplaces with seller-specific promotions, automatic cart rules or B2B price lists need a real pricing service with explainable rule evaluation.
