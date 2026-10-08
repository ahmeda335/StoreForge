# StockSync: a sample third-party app

A small app that keeps a "warehouse" in sync with a StoreForge store, built from the OUTSIDE with nothing but
the public contract: OAuth install, the developer API (`/api/2026-10`) and signed webhooks. Python standard
library only, deliberately: no dependency to install, and proof the platform isn't tied to our stack.

It exercises what every integration must get right:

| Concern | Where |
|---|---|
| OAuth authorization code + PKCE, `state` against CSRF | `install_redirect`, `complete_install` |
| App-owned webhook subscriptions (`orders/paid`, `inventory_levels/update`) | `complete_install` |
| Webhook receiver checklist: signature over the raw body, replay window, dedup by event id, answer fast | `handle_webhook` |
| Idempotent writes (one `Idempotency-Key` per restock, reused on retries) | `restock` |
| Back off on 429 using `Retry-After` | `api` |
| Webhooks are hints: reconcile with the "changed since" feed | `reconcile` |

## Run it locally

```bash
# StoreForge with local webhook receivers allowed (never in production):
WEBHOOKS_REQUIRE_HTTPS=false WEBHOOKS_ALLOWED_PRIVATE_CIDRS=127.0.0.0/8 ./mvnw spring-boot:run

# 1. Register the app (as any signed-in user) → note clientId / clientSecret (shown once):
#    POST /admin/v1/apps {"name":"StockSync","redirectUris":["http://127.0.0.1:9095/oauth/callback"],
#                         "scopes":["read_orders","read_inventory","write_inventory"]}
CLIENT_ID=... CLIENT_SECRET=... python3 samples/stocksync/stocksync.py serve

# 2. As a merchant, open in a browser:
#    http://127.0.0.1:9095/install?store=<your-handle>.storeforge.localhost
#    → sign in, review the consent, Install.

# 3. Place and pay an order: StockSync logs orders/paid and inventory_levels/update.
CLIENT_ID=... CLIENT_SECRET=... python3 samples/stocksync/stocksync.py reconcile
CLIENT_ID=... CLIENT_SECRET=... python3 samples/stocksync/stocksync.py restock <inventory-item-id> 10
```

State (installation, webhook secrets, seen event ids, shipments) lives in `stocksync-state.json`. A real app
would use a database and encrypt the access token and webhook secrets at rest.
