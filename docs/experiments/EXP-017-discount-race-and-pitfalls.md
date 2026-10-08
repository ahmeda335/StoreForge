# EXP-017: The last coupon, two warehouse workers, and three pitfalls

- **Date:** 2026-10-08 · **Phase:** P13 · **Setup:** Testcontainers PostgreSQL; `DiscountIT`, `ShipmentIT`.

## Experiment
1. **Last coupon:** a code with `usageLimit = 1`, many checkouts completing at once. Then mutation **DC1**: drop `usage_count < usage_limit` from the conditional update.
2. **Last units:** an order of 3 lamps; two concurrent shipments of 2 each. Then mutation **SH1**: read the order without its row lock (`find…` instead of `lock…`), three runs.

## Expected behavior
- Exactly one checkout gets the code; the others get `409 discount_unavailable`. DC1 fails the test.
- One shipment 201, the other 422 `quantity_exceeds_remaining`. SH1 fails the test.

## Actual behavior

| Case | Result |
|---|---|
| Last coupon, as built | 1 success, the rest 409 |
| DC1, end-to-end race test | **Survived** (still exactly 1 success) |
| DC1, direct test `theConditionalUpdateAloneDecidesTheLastUse` | Failed (10 redemptions of a 1-use code) |
| Shipments, as built | 201 + 422 |
| SH1 | Failed 3/3 runs (both 201: 4 lamps shipped of 3) |

Three pitfalls surfaced while building:

| Finding | Kind |
|---|---|
| Codes typed in lowercase never matched | Product bug |
| Optional booleans omitted from JSON → 400 | Product bug |
| Validation errors asserted as 400 | Test bug (they are 422) |

## What failed / why
- **DC1 survived the race test** because completions in one store are serialized *before* they reach the discount: first by the inventory row locks of the same variant, then by the per-store order-number counter (`store_counters`, ADR-014). The race the test was meant to create never happened. The conditional update is real protection, but that test couldn't prove it.
- **Lowercase codes:** the column is `citext`, but the parameter binds as `text`, and `citext = text` resolves to the **text** operator: case-sensitive. Fixed with `code = CAST(:code AS citext)`. The same trap was documented for emails in P2 (`EmailAddress`) and bit again.
- **Booleans:** a missing JSON field for a primitive `boolean` record component fails in Jackson 3; `@Nullable Boolean` with a default fixes it.

## How it was detected
Mutation runs (DC1 green), a test with a lowercase code, a request without the optional flags.

## How the system recovered
N/A.

## What we changed
- Added `theConditionalUpdateAloneDecidesTheLastUse`: 10 threads behind a `CyclicBarrier` call `redeem` directly. DC1 now fails.
- `CAST(:code AS citext)` in every code lookup; `@Nullable Boolean` for optional flags.
- **Lesson:** a concurrency test proves only the guard it actually reaches. When an earlier lock serializes the flow, test the inner guard on its own.
