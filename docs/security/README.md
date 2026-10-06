# Security (as built)

Threat analysis and target controls: [blueprint/18-security.md](../blueprint/18-security.md).

## P1 posture

| Control | Implementation | Verified by |
|---|---|---|
| Least-privilege DB access | Runtime role `storeforge_app` cannot run DDL; owner role only for migrations | `DatabaseRolesIT` |
| DB-side guards | `statement_timeout=10s`, `idle_in_transaction_session_timeout=30s` on the app role | `DatabaseRolesIT` |
| No internals in errors | 500 responses opaque; `server.error.include-stacktrace/message=never` | `ErrorResponseIT` |
| Log-forging resistance | `X-Request-Id` accepted only if it matches `[A-Za-z0-9-]{8,64}` | `RequestIdsTest` |
| Operational endpoints not public | Actuator on management port 8081; Compose binds it to 127.0.0.1 | `OperationalEndpointsIT` |
| Container hardening | Non-root user, JRE-only runtime image, exec-form entrypoint | Dockerfile review |
| Secrets out of Git | `.env` gitignored, `.env.example` with dev-only values | `.gitignore` |

## P2 additions (identity)

| Control | Implementation | Verified by |
|---|---|---|
| Password storage | Argon2id (`DelegatingPasswordEncoder`, upgradeable); 12–128 chars | `RegistrationIT` |
| Token forgery resistance | ES256 pinned, own JWKS only, RFC 9068 `typ/iss/aud/client_id/exp` | `AccessTokenSecurityIT` (alg=none, foreign key with our kid, HS256 confusion, expired, wrong aud/iss/typ, tampered) |
| Immediate revocation | Security stamp + ACTIVE check per request; reset revokes all refresh tokens | `PasswordResetIT`, `AccessTokenSecurityIT` |
| Refresh token theft | Hashed at rest, single-use rotation, family revocation on reuse | `TokenRefreshIT` |
| CSRF on cookie endpoints | `SameSite=Strict`, `Path=/auth/v1/token`, custom header, Origin allow-list | `TokenRefreshIT` |
| XSS token theft | Refresh token `HttpOnly`; access token kept in memory by the SPA | Cookie attribute assertions |
| Credential stuffing / brute force | Per-IP + per-email token buckets; exponential temporary lockout | `LoginIT`, `RateLimiterTest` |
| Account enumeration | Uniform responses for register/reset/resend/login; dummy-hash timing | `RegistrationIT`, `LoginIT`, `PasswordResetIT` |
| Secrets in logs | Redacted `toString()`; IDs only in log fields | `SensitiveDataLoggingIT` |
| Secrets in URLs | Email links use URL fragments; tokens sent in POST bodies | Design (ADR-008) |
| Mass assignment | Explicit request records; unknown JSON properties rejected | `RegistrationIT` |

**Accepted risks / follow-ups:** in-memory rate limits (per instance, P12); `X-Forwarded-For` must be overwritten by the proxy (P16); ephemeral signing key allowed by default for local dev; production must set `AUTH_SIGNING_JWK` and `AUTH_ALLOW_EPHEMERAL_SIGNING_KEY=false` (P16).

## P3 additions (tenancy & authorization)

| Control | Implementation | Verified by |
|---|---|---|
| Cross-tenant access (IDOR, OWASP API1) | Membership check per request, 404 for non-members | `TenantIsolationIT` (auto-discovers every `{storeId}` endpoint) |
| Defense in depth for forgotten filters | PostgreSQL RLS (ENABLE + FORCE) on tenant data; transaction-local tenant context | `RowLevelSecurityIT` (meta-test + behaviour) |
| Cross-tenant references | Composite foreign keys `(store_id, role_id)` | `TenantIsolationIT.otherStoresResourceIdsAreNotFoundInsideMyStore` |
| Function-level authorization (API5) | Permission per operation in services | `StaffManagementIT.permissionsAreEnforcedPerEndpoint` |
| Privilege escalation | Subset rule (`StoreMember.covers`), owners-only for owners | `StaffManagementIT.membersCannotGrantMoreThanTheyHave` |
| Invitation hijacking | Bound to the invited verified email; single use; hashed token | `StaffManagementIT.invitationsOnlyWorkForTheInvitedEmailAndOnlyOnce` |
| Tamper-evident history | Audit log append-only at the DB privilege level | `RowLevelSecurityIT.theAuditLogIsAppendOnlyForTheApplication` |
| Handle squatting on platform names | Reserved handle list | `StoreLifecycleIT` |

## P4 additions (catalog, first public API)

| Control | Implementation | Verified by |
|---|---|---|
| Public API exposure | Storefront DTOs exclude SKU, status and internal timestamps; DRAFT/ARCHIVED products are 404 | `StorefrontCatalogIT` |
| Storefront tenant isolation | Store from the hostname only; reads in a tenant transaction (RLS) | `StorefrontCatalogIT.productHandlesResolvePerStore` |
| Injection via filters/sort | Allow-listed `sort`/`status`; `ILIKE` wildcards escaped; bound parameters | `ProductAdminIT.listFiltersSortsAndPaginates` |
| Unbounded payloads (API4) | Max 100 variants, 3 options, size limits on all strings, page size ≤ 100 | Bean Validation + domain rules |
| Lost updates | ETag/If-Match + `@Version` | `ProductAdminIT` |
| Catalog endpoints in the isolation suite | Sample bodies for every catalog write | `TenantIsolationIT` |

**Not yet:** storefront rate limiting (scraping) arrives with Redis (P12). HTML in descriptions is stored as given and must be sanitized before any rendering (P14 sanitization; the API itself returns JSON only).

## P5 additions (inventory)

| Control | Implementation | Verified by |
|---|---|---|
| Business-logic abuse: oversell | Conditional update + CHECK constraint | `InventoryConcurrencyIT`, EXP-004 |
| Tamper-evident stock history | Ledger append-only at privilege level | REVOKE in V5 |
| Narrow RLS bypass for workers | Definer function returns store ids only, pinned `search_path`, EXECUTE only for the app role; owner role NOLOGIN, not grantable to the app | [ADR-011](../decisions/ADR-011-cross-tenant-background-work.md); `SET ROLE` denied (manual check) |
| Foreign variant ids | Composite FK `(store_id, variant_id)` → 404 | `InventoryAdminIT` |

## P6 additions (observability)

| Control | Implementation |
|---|---|
| No secrets in telemetry | JDBC spans without parameter values; log redaction rules apply to OTLP logs too (same records) |
| Telemetry endpoints not public | Compose binds Prometheus/Tempo/Loki APIs to 127.0.0.1; Grafana's default admin/admin is dev-only |
| Telemetry failure isolation | Async batch export: a Collector outage never blocks requests ([EXP-005](../experiments/EXP-005-collector-unavailable.md)) |

## P7 additions (cart & checkout)

| Control | Implementation | Verified by |
|---|---|---|
| Anonymous ownership of carts | Random cart token, hashed at rest, required on every call, `no-store`; wrong token = unknown id (404) | `CartIT` |
| Cross-store carts | Hostname-resolved store + RLS | `CartIT.theCartTokenIsRequiredAndCartsDoNotCrossStores` |
| Price tampering | Prices only from the catalog; the client only confirms `expectedTotal` | `CheckoutIT` |
| Duplicate submission | Required `Idempotency-Key`; row lock + state machine | `CheckoutIdempotencyIT` |
| Idempotency-key leakage | Fingerprint includes the cart-token hash, so no replay to other shoppers | `CheckoutIdempotencyIT.aKeyCannotBeReusedForADifferentRequestOrByAnotherShopper` |
| Customer PII | Store-scoped, `customers:read` permission, storefront responses never expose other customers | `TenantIsolationIT`, `CheckoutIT` |

**Not yet:** storefront rate limiting (cart creation and checkout-completion abuse, card testing later) arrives with Redis in P12.

## P8 additions (orders)

| Control | Implementation | Verified by |
|---|---|---|
| Order history tamper resistance | `order_items`, `order_events` UPDATE/DELETE revoked; audit entries for merchant transitions | `OrderPlacementIT.orderLinesAreImmutableForTheApplication` |
| Function-level authorization | `orders:read` vs. `orders:write` | `OrderLifecycleIT.readOnlyStaffCannotChangeOrders` |
| Order IDOR across stores | Tenant path + RLS | `TenantIsolationIT` (order endpoints with sample bodies) |
| Money integrity | Totals CHECK, paid orders can't be cancelled without refund | `OrderStateMachineTest`, `OrderLifecycleIT` |

## Not yet (by design)
MFA (P14), dependency and secret scanning in CI (P14), TLS (P16 via the reverse proxy).

## Accepted risk
Actuator health shows component details including exception class names. This is acceptable only because the management port is private.
