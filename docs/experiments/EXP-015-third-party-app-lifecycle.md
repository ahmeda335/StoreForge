# EXP-015: A third-party app's whole life (StockSync)

- **Date:** 2026-10-08 · **Phase:** P12
- **Setup:**
  - the app from the jar, Compose PostgreSQL, Mailpit and Redis, with local webhook receivers allowed (`127.0.0.0/8`);
  - [StockSync](../../samples/stocksync/README.md) as a separate process;
  - a driver script plays the developer, the merchant's browser (cookie jar, no auto-redirects) and a customer.

## Experiment
1. A developer registers StockSync.
2. A merchant opens StockSync's `/install?store=…`: every redirect is followed by hand through login, consent and approve, to the app's callback.
3. A customer buys 2 lamps, and the merchant marks the order paid.
4. StockSync reconciles, then restocks +10 through the API.
5. StockSync makes 50 calls in a burst.
6. The merchant uninstalls the app, then changes stock again.

## Expected behavior
- The consent names the store, the app and the scopes.
- StockSync receives signed `orders/paid` and `inventory_levels/update` events.
- The restock is applied once and audited as the app.
- The burst ends with every call succeeding after backing off on 429.
- After the uninstall: 401, no subscriptions left, no further webhooks.

## Actual behavior

| Step | Observed |
|---|---|
| Install hops | `stocksync /install` → `authorize → /oauth/login` → `login → saved authorize` → `authorize → /oauth/consent` → `approve → …/oauth/callback` → **"StockSync installed on store shop226755…"** |
| Consent | "StockSync wants to access Lamp Shop (shop226755) and will be able to: read_orders, read_inventory, write_inventory" |
| Merchant view | `[('StockSync', ['read_inventory','read_orders','write_inventory'])]` |
| Events at StockSync | `inventory_levels/update … onHand=3 (SOLD -2)`, `orders/paid #1001 (49.00 USD) -> shipment recorded`, and after the restock `onHand=13 (RECEIVED +10)`: all signature-verified |
| Restock | `HTTP 200 … onHand 13` (one `Idempotency-Key`) |
| Burst of 50 | 4× "429 rate limited; waiting 1s as told", then **all 200** after 5.2 s |
| Audit | `inventory.adjusted` **APP**, `order.marked_paid` USER, `webhook.subscription_created` **APP** |
| Uninstall | app call → **401**; app subscriptions left: **0**; no webhook for the later stock change |

## What failed / why
Nothing. The lifecycle behaves as designed.

Two things were confirmed that the integration tests don't show:
- the flow works across *two* separate processes, with real HTTP redirects between them;
- a client that honours `Retry-After` completes a burst without errors.

## How it was detected
N/A.

## How the system recovered
N/A.

## What we changed
Nothing. The sample app became the reference integration ([samples/stocksync](../../samples/stocksync/README.md)).
