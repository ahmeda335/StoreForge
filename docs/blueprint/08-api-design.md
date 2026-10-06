# 08 — API Design

The living conventions (what is implemented now) are in [`docs/api/conventions.md`](../api/conventions.md). This chapter is the design.

## Four APIs, four security models

| API | Base path | Caller | Auth | Tenant from | Rate limit key | Why different |
|---|---|---|---|---|---|---|
| Storefront | `/storefront/v1` (host-resolved store) | Anonymous shoppers, customers | None / cart token / customer JWT (`aud=storefront:{id}`) | Host header | IP + store | Public, cacheable, abuse-prone (scraping, card testing) |
| Merchant Admin | `/admin/v1/stores/{storeId}` | Merchant SPA | User JWT (`aud=merchant`) | Path + membership | User + store | Humans with roles; CSRF-relevant cookie refresh flow |
| Developer | `/api/{version}/stores/{storeId}` (e.g. `/api/2026-10/...`) | Third-party systems | API key / OAuth token | Credential (path must match) | App installation + store | Machines, scopes, stable versioned contract, high volume |
| Platform Admin | `/platform/v1` | Operators | Admin JWT (`aud=platform`), MFA | Explicit parameter | Admin user | Cross-tenant power, so the strictest audit, and network-restricted in prod |
| Auth | `/auth/v1` | All humans | Credentials | — | IP + email | Credential attacks |

Sharing one security model would either over-expose (public rules on admin) or over-restrict (MFA on storefront). Each API gets its own Spring Security `SecurityFilterChain` matched by path.

## REST conventions

- Resources are plural nouns: `/products`, `/products/{productId}/variants`. Nesting stops at one level below the tenant (`/stores/{id}/orders/{orderId}/refunds`).
- Actions that are not CRUD become sub-resources: `POST /orders/{id}/cancellation`, `POST /checkouts/{id}/completion`, `POST /payments/{id}/captures`. This avoids verbs in URLs and gives every action a resource you can GET later.
- JSON `camelCase`; timestamps ISO-8601 UTC; money as `{ "amount": "19.99", "currency": "USD" }` (string decimal; minor units internally). IDs are opaque strings.

## HTTP semantics and status codes

| Code | Use |
|---|---|
| 200 | GET, PUT/PATCH returning the resource |
| 201 + `Location` | Created |
| 202 | Accepted for async processing (e.g. bulk import) |
| 204 | DELETE |
| 400 | Malformed JSON, wrong types |
| 401 | Missing/invalid credentials (`WWW-Authenticate`) |
| 403 | Authenticated, in the right tenant, lacking permission/scope |
| 404 | Not found **or belongs to another tenant** |
| 409 | Conflict: optimistic-lock version mismatch, duplicate unique value, idempotency key in flight, invalid state transition |
| 412 | `If-Match` ETag mismatch (optimistic concurrency via HTTP) |
| 422 | Semantically invalid (validation errors, insufficient stock, idempotency key reused with a different body) |
| 429 | Rate limited, with `Retry-After` and `RateLimit-*` headers |
| 500/503 | Server error / dependency unavailable (with `Retry-After` when meaningful) |

## Errors: RFC 9457 Problem Details

```json
{
  "type": "https://docs.storeforge.dev/errors/insufficient-stock",
  "title": "Insufficient stock",
  "status": 422,
  "detail": "Variant 0192f... has 0 available, 1 requested.",
  "instance": "/storefront/v1/checkouts/0192f.../completion",
  "code": "insufficient_stock",
  "requestId": "6f1c7d0e8a8b4b1e",
  "errors": [ { "field": "items[0].quantity", "code": "insufficient_stock", "message": "..." } ]
}
```
`code` is a stable machine-readable string that clients switch on. Messages may change. 5xx responses never include stack traces or SQL.

## Pagination, filtering, sorting, searching

- **Cursor (keyset) pagination** for lists: `?limit=50&cursor=<opaque>`. Response: `{ "data": [...], "page": { "nextCursor": "...", "hasMore": true } }`. The cursor encodes `(sort_value, id)`. Offset pagination degrades (`OFFSET 100000` scans 100k rows) and skips or duplicates items under concurrent inserts.
- `limit` default 25, max 100. Server-enforced.
- Filtering: explicit allow-listed params (`?status=ACTIVE&createdAfter=...`). Never pass through arbitrary field names to SQL.
- Sorting: `?sort=-createdAt` from an allow list mapped to indexed columns.
- Search: `?q=` on endpoints that support it (catalog, orders by number/email).

## Versioning

- Admin/Storefront (first-party, deployed with the backend): `/v1` major versions only.
- Developer API: **date-based versions** (`/api/2026-10/`). Each version is supported ≥ 12 months. Breaking changes ship only in new versions. Webhook payloads carry the subscription's `api_version`. The rationale: third parties upgrade on their own schedule.
- Additive changes (new fields, new endpoints) are not breaking. Clients must ignore unknown fields.

## Validation

- Syntactic validation in DTOs (Bean Validation: `@NotBlank`, `@Size`, `@Email`), returning 400/422 with field errors.
- Semantic/business validation in the domain (`Quantity` cannot be negative; state transitions), returning 422/409.
- Unknown JSON properties: rejected on the admin/developer APIs (`FAIL_ON_UNKNOWN_PROPERTIES`) to catch client bugs early. This trades leniency for strictness, and is documented.

## Idempotency keys

`Idempotency-Key: <uuid>` header is **required** on `POST /checkouts/{id}/completion`, payment, and refund endpoints; optional on other POSTs. Semantics are in [06-database.md](06-database.md#idempotency-and-retries). Keys are scoped by `(store, principal, endpoint)` and kept for 24h.

## Correlation / request IDs

- Inbound `X-Request-Id` is accepted if it matches `[A-Za-z0-9-]{8,64}`. Otherwise (missing or malformed) a new one is generated. It is echoed in the response header and in every error body. It goes into the log MDC.
- W3C `traceparent` is handled by OpenTelemetry. The request ID is for humans (support tickets); the trace ID is for tooling. Logs contain both.

## Rate limiting

Token bucket per key (see the table above). Headers: `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, `Retry-After`. Separate stricter buckets for `/auth/*` and checkout completion (card-testing defense).

## Example endpoints (design only)

```text
# Auth
POST   /auth/v1/registrations
POST   /auth/v1/token                       (login)
POST   /auth/v1/token/refresh
POST   /auth/v1/token/revocation            (logout)
POST   /auth/v1/email-verifications/confirmation   {token}          (token never in the URL: ADR-008)
POST   /auth/v1/password-resets            {email}
POST   /auth/v1/password-resets/confirmation       {token, newPassword}

# Merchant admin
POST   /admin/v1/stores                                  create store (caller becomes owner)
GET    /admin/v1/stores                                  stores I belong to
GET    /admin/v1/stores/{storeId}/products?status=ACTIVE&sort=-createdAt&limit=25&cursor=...
POST   /admin/v1/stores/{storeId}/products
PATCH  /admin/v1/stores/{storeId}/products/{productId}   If-Match: "v7"
POST   /admin/v1/stores/{storeId}/inventory-items/{id}/adjustments   {delta, reason}
GET    /admin/v1/stores/{storeId}/orders/{orderId}
POST   /admin/v1/stores/{storeId}/orders/{orderId}/cancellation
POST   /admin/v1/stores/{storeId}/orders/{orderId}/refunds          Idempotency-Key
POST   /admin/v1/stores/{storeId}/members/invitations
POST   /admin/v1/stores/{storeId}/webhook-subscriptions
POST   /admin/v1/stores/{storeId}/webhook-deliveries/{id}/replay
POST   /admin/v1/stores/{storeId}/api-keys                         (secret shown once)

# Storefront (Host: acme.storeforge.localhost)
GET    /storefront/v1/products?q=shirt&collection=summer
GET    /storefront/v1/products/{handle}
POST   /storefront/v1/carts                                  → {cartToken}
POST   /storefront/v1/carts/{cartId}/items                   X-Cart-Token
POST   /storefront/v1/checkouts                              {cartId, email, address}
POST   /storefront/v1/checkouts/{id}/completion              Idempotency-Key  → order + payment client secret
GET    /storefront/v1/orders/{orderId}?token=...             (order status page token)

# Developer
GET    /api/2026-10/stores/{storeId}/orders?updatedAfter=...
POST   /api/2026-10/stores/{storeId}/inventory-items/{id}/adjustments

# Platform admin
GET    /platform/v1/stores?status=ACTIVE
POST   /platform/v1/stores/{storeId}/suspension
GET    /platform/v1/jobs?status=DEAD

# Provider callbacks
POST   /webhooks/payments/stripe          (signature-verified, no user auth)
```
