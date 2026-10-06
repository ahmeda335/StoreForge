# 24 — Frontend (Angular)

The frontends exist to **exercise the platform**: they make flows observable end-to-end (browser → trace) and reveal API design problems early. Visual polish is a non-goal: use Angular Material defaults.

## Structure

One Angular workspace in `frontend/` with two applications and a shared library:

```text
frontend/
├── projects/merchant-admin/     (served at admin.storeforge.localhost)
├── projects/storefront/         (served at {store}.storeforge.localhost)
└── projects/api-client/         generated from OpenAPI (openapi-generator typescript-angular) — no hand-written HTTP models
```
Standalone components, signals for state, `HttpClient` interceptors for auth (access token in memory, refresh on 401 once, then logout), `X-Request-Id` generated per request (and shown in error toasts so you can find the trace).

## Merchant Admin

| Area | Screens | Platform feature exercised |
|---|---|---|
| Auth | Register, verify email, login, reset password | Token lifecycle, refresh rotation |
| Store management | Create store, switch store, settings, domains | Tenancy, store resolution |
| Staff | Members, invitations, roles editor | RBAC, anti-escalation (UI hides, server enforces) |
| Products | List (keyset pagination, filters, search), editor with variants, image upload | Pagination contract, optimistic locking (409 → "reload changes" dialog), presigned uploads |
| Inventory | Levels per variant, adjust, ledger history | Inventory ledger |
| Orders | List, detail, cancel, refund, fulfil | State machines, idempotency keys on refund |
| Customers | List, detail with orders | PII handling |
| Analytics | Simple sales by day | Read models |
| Integrations | API keys (secret shown once), installed apps, webhook subscriptions, delivery history with replay, secret rotation | Developer platform |

## Storefront

Product listing (search, filters), product detail (variant selection, stock badge), cart (cart token in `localStorage`; it is a bearer token, acceptable for carts and documented), checkout (email, address, shipping rate, Stripe Payment Element or the fake provider's test form), order confirmation (polls order status until PAID, which demonstrates async payment).

## Testing

Component tests only where logic exists; a handful of **Playwright** E2E tests for the purchase flow and the admin product flow run against the Compose stack in CI.

## When to build it

Not first. Frontend is a parallel track in the roadmap: **track F1 (merchant admin)** can start once orders exist (after Phase 8); **track F2 (storefront)** once payments exist (after Phase 10). Until then, use HTTP files (`http/*.http` for the IntelliJ HTTP client) and the API tests.
