# Developer API and OAuth apps (implemented, P12)

Design: [domains/developer.md](../domains/developer.md). Machine-readable contract: `GET /developer/openapi`. Try it: [`http/developer.http`](../../http/developer.http). Sample app: [StockSync](../../samples/stocksync/README.md).

## Credentials

| Credential | Who creates it | Format | How |
|---|---|---|---|
| API key | Merchant (`apps:manage`), for their own scripts | `sfk_…` | `POST /admin/v1/stores/{storeId}/api-keys {name, scopes}`; token shown once |
| App access token | Issued at OAuth installation | `sfat_…` | Authorization code + PKCE, see below |

Send either one as `Authorization: Bearer <token>`. A credential belongs to exactly one store. Revoking a key or uninstalling an app takes effect on the very next request.

**Scopes:**

| Scope | Allows | Equals permission |
|---|---|---|
| `read_products` | Read products | `products:read` |
| `write_products` | Write products | `products:write` |
| `read_orders` | Read orders | `orders:read` |
| `write_orders` | Write orders | `orders:write` |
| `read_inventory` | Read inventory | `inventory:read` |
| `write_inventory` | Write inventory | `inventory:write` |
| `read_customers` | Read customer data (PII) | `customers:read` |

You can never grant a scope you don't hold yourself.

## Endpoints (`/api/2026-10/stores/{storeId}`)

| Method & path | Scope | Notes |
|---|---|---|
| `GET /products`, `GET /products/{id}` | `read_products` | Keyset pagination |
| `GET /orders?updatedAfter=&limit=&cursor=` | `read_orders` | **Sync feed**: changed after `updatedAfter`, oldest change first; follow `page.nextCursor` |
| `GET /orders/{id}` | `read_orders` | With line items, totals, refunded amount |
| `GET /inventory-items?updatedAfter=&limit=&cursor=`, `GET /inventory-items/{id}` | `read_inventory` | Sync feed |
| `POST /inventory-items/{id}/adjustments` + **`Idempotency-Key`** | `write_inventory` | `{delta, reason: RECEIVED\|CORRECTION\|DAMAGED\|RETURNED, note?}`; a replay returns the first response (`Idempotent-Replayed: true`); never pushes stock below reserved |
| `GET/POST /webhook-subscriptions`, `DELETE /webhook-subscriptions/{id}` | per topic | The credential's own subscriptions; `orders/*`, `refunds/create` need `read_orders`; `inventory_levels/update` needs `read_inventory`; deleted with the credential |

**Errors (Problem Details, `code`):**

| Status | Code |
|---|---|
| 401 | `unauthenticated` (+ `WWW-Authenticate: Bearer`) |
| 403 | `insufficient_scope` (+ `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"`) |
| 404 | `store_not_found` (a store that isn't the credential's) |
| 404 | `unsupported_api_version` |
| 429 | `rate_limited` (+ `Retry-After`) |

**Response headers:**
- `X-StoreForge-Api-Version: 2026-10`;
- `X-RateLimit-Limit` (40) and `X-RateLimit-Remaining`.

The budget is a token bucket per credential (40 burst, 2/s), plus a store-wide bucket (100, 5/s) shared by all of a store's credentials. **On 429, wait `Retry-After` seconds, and keep the same `Idempotency-Key` when retrying writes.**

## Building an app (OAuth 2.0 authorization code + PKCE)

1. **Register:** `POST /admin/v1/apps {name, redirectUris, scopes}`.
   - The response holds `clientId` and `clientSecret`; the secret is shown once.
   - Redirect URIs must be exact: https, or http only for localhost.
2. **Install:** send the merchant's browser to:
   ```
   /oauth2/authorize?response_type=code&client_id=…&redirect_uri=…&scope=read_orders%20write_inventory
                    &state=<random, check it later>&code_challenge=<S256(verifier)>&code_challenge_method=S256
                    &store=<shop-handle>.storeforge.localhost
   ```
   The merchant signs in and sees a consent page (store, app, scopes). They can install only if they hold `apps:manage` and every requested scope's permission.
3. **Callback:** `redirect_uri?code=…&state=…`. Check `state` first.
4. **Token:** `POST /oauth2/token` with HTTP Basic (`client_id:client_secret`) and the form fields `grant_type=authorization_code`, `code`, `redirect_uri` and `code_verifier`. The response:
   ```json
   {"access_token": "sfat_…", "token_type": "Bearer", "scope": "read_orders write_inventory",
    "store_id": "0192…", "expires_in": 315359999}
   ```
   - The token is an **offline installation token**: valid until the merchant uninstalls (no refresh token).
   - Codes are single-use, expire after 5 minutes, and require the matching `code_verifier`.
5. **Merchants manage installations:**
   - `GET /admin/v1/stores/{storeId}/app-installations`;
   - `DELETE …/{installationId}` (uninstall): the token is revoked at once and the app's webhook subscriptions are deleted. Reinstalling replaces the previous token.

Webhook receiving: see the [receiver checklist](webhooks.md#receiving-safely-checklist).
