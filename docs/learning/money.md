# Representing Money

**Problem.** `0.1 + 0.2 = 0.30000000000000004` in binary floating point. In a commerce system, prices are multiplied by quantities, summed over lines, discounted, taxed and refunded. Cent errors compound and become reconciliation nightmares.

**Why we need it.** Prices (P4), order totals (P8), payments and refunds (P10), discounts (P13).

**Alternatives.** `double` (wrong); `BigDecimal` everywhere (correct but easy to misuse: `19.9` vs `19.90` aren't `equals()`, every division needs a rounding mode); a library such as JSR-354 Moneta (powerful, heavier); **integers in minor units**.

**Decision.** `Money(long amountMinor, Currency currency)`. The database uses `bigint` minor units. The wire format is a decimal **string** plus currency.

**Mental model.**
- Minor units: USD has 2 decimals (1999 = $19.99), JPY has 0 (1500 = ¥1500), some currencies have 3. `Currency.getDefaultFractionDigits()` tells which.
- **Parse strictly:** `"19.999"` USD is rejected, not rounded. Silent rounding of input is a bug factory.
- **Arithmetic is exact:** `Math.addExact`/`multiplyExact` throw on overflow instead of wrapping.
- **Never mix currencies:** `plus()` throws on mismatch. Conversions are explicit operations with rates and dates (out of scope).
- **JSON strings, not numbers:** many JSON parsers (JavaScript!) read numbers as doubles. `"19.99"` survives every client.
- Rounding happens only where business rules say so (percentage discounts, tax, P13), with an explicit mode.

**Project usage.** `shared.money.Money`; `Variant.priceMinor`; `CatalogDtos.MoneyDto`; CHECK constraints `price_minor >= 0`.

**Failure cases.** A store changing currency after products exist (not supported: currency is fixed at store creation). Percentage math on minor units needs a defined rounding rule (P13).

**Testing.** `MoneyTest` (parsing per currency, exact arithmetic, currency mismatch, formatting); `ProductAdminIT` (`"21.50"` round-trips, `"1.999"` rejected).

**Observability.** None specific. Money values appear in audit details as decimal strings.

**When NOT to use it.** Never use floating point for money. For FX rates and tax rates (not money themselves), `BigDecimal` with explicit scale is appropriate.
