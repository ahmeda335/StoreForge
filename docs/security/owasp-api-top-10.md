# OWASP API Security Top 10 (2023): controls and the tests that guard them

Each risk names the control and the test that fails if the control is removed. Most were mutation-checked (EXP-016, EXP-021).

| Risk | StoreForge control | Regression tests |
|---|---|---|
| **API1** Broken object level authorization | Store id in every admin path + `StoreAccess` + forced RLS; 404 for other stores | `TenantIsolationIT` (every `/admin/v1/stores/{storeId}` endpoint, discovered), `RowLevelSecurityIT`, `ProductImagesIT` |
| **API2** Broken authentication | ES256 JWTs pinned (alg, iss, aud, client_id), security stamp, refresh reuse detection, Argon2id, per-IP/email limits with trusted proxies, mandatory TOTP for staff | identity ITs, `HttpEdgeIT`, `StorefrontRateLimitIT`, `PlatformAdminIT` |
| **API3** Broken object property level authorization | Per-use-case request records, unknown fields rejected (400), response DTOs without internals | `PropertyLevelAuthorizationIT` |
| **API4** Unrestricted resource consumption | 1 MB bodies, page caps (100), search caps (q 200, offset 1,000), upload limits (10 MB, 20 images), statement timeout, rate limits | `HttpEdgeIT`, `StorefrontRateLimitIT`, `ProductImagesIT`, `RateLimitIT` |
| **API5** Broken function level authorization | Permissions checked in services; separate chains/audiences per API | `FunctionLevelAuthorizationIT` (every endpoint: 401 without credentials, merchant token on platform, permissionless role → 403) |
| **API6** Unrestricted access to sensitive business flows | Limits on checkout completion (card testing), discount attempts (guessing), cart creation; reservations only at completion | `StorefrontRateLimitIT`, `DiscountIT` |
| **API7** Server-side request forgery | Resolved-address check as the HTTP client's DNS resolver, no redirects; presigned uploads instead of "import from URL" | `WebhookDeliveryIT`, `EndpointPolicyTest` |
| **API8** Security misconfiguration | Actuator on the private port only; strict CSP/headers; CORS per API; Problem Details without internals; development secrets refused | `FunctionLevelAuthorizationIT`, `HttpEdgeIT`, `DevelopmentSecretsGuardTest` |
| **API9** Improper inventory management | Versioned developer API (`/api/2026-10`), OpenAPI generated from code, endpoint discovery in tests (new endpoints are covered automatically) | `FunctionLevelAuthorizationIT`, `TenantIsolationIT`, `DeveloperApiIT` (every developer endpoint declares a scope) |
| **API10** Unsafe consumption of APIs | Provider webhooks verified (HMAC, timestamp, dedup), provider calls with timeouts + breaker, responses treated as untrusted | payments ITs, `StripePaymentProviderContractTest` |

See also the [threat model](threat-model.md).
