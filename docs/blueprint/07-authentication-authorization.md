# 07 — Authentication and Authorization

## Where authentication ends and authorization begins

- **Authentication** answers *who is calling?* It ends when the request holds a verified **principal**: `UserPrincipal(userId)`, `CustomerPrincipal(storeId, customerId)`, `AppPrincipal(storeId, installationId, scopes)`, or `AdminPrincipal`. It is done by Spring Security filters, before any controller.
- **Authorization** answers *may this principal do this action on this resource in this store?* It needs the **resource** (and its store), so it cannot be fully done in a filter. Coarse checks (is the principal of the right kind for this API?) happen in the security filter chain. Fine checks (`orders:write` in store X, does this order belong to store X?) happen in the **application service**, which every entry point (REST, jobs, admin) must go through.

A valid token proves identity. It never proves permission.

## Merchant authentication (Merchant Admin API)

| Topic | Design |
|---|---|
| Registration | `POST /auth/v1/registrations` {email, password}. Normalize email; validate password (length ≥ 12, check a breached-password list later); hash; create user `UNVERIFIED`; enqueue verification email. Response is the same for an existing email ("check your inbox") to prevent account enumeration |
| Password hashing | **Argon2id** (Spring Security `Argon2PasswordEncoder`) or BCrypt cost ≥ 12. Use the delegating encoder (`{argon2}` prefix) so the algorithm can be upgraded and rehashed on login |
| Email verification | Random 32-byte token; store **SHA-256 hash** only; expiry 24h; single use (`used_at`) |
| Password reset | Same token mechanism, 30 min expiry. On success: **revoke all refresh tokens** for that user. Same response whether the email exists or not |
| Login | `POST /auth/v1/token` {email, password} → access token (JWT, 15 min, RFC 9068 `typ: at+jwt`) in the body + refresh token (opaque, 30 days sliding) in an `HttpOnly; Secure; SameSite=Strict; Path=/auth/v1/token` cookie |
| Access tokens | JWT signed with **ES256**; claims `sub`, `iss`, `aud=merchant`, `exp`, `iat`, `jti`. **No permissions in the token**: they change when roles change, and the token would be stale for 15 min. Membership is looked up (and cached briefly) per request |
| Key management | Signing key pair from config/secret store with a `kid`; JWKS endpoint lists current + previous key for rotation |
| Refresh tokens | Opaque random string; DB stores the hash, `family_id`, `user_id`, `expires_at`, `used_at`, `revoked_at` |
| Rotation | Each refresh returns a **new** refresh token and marks the old one used. **Reuse detection:** presenting an already-used token means it was stolen, so revoke the entire family |
| Logout | Revoke the current refresh token family; clear the cookie. Access token expires naturally (≤15 min). That window is the accepted trade-off of stateless JWTs |
| Revocation | Refresh: DB. Access: short TTL + per-user **security stamp** (`sstamp` claim must equal the user's current stamp; incremented on password reset) and an ACTIVE-status check on each request ([ADR-008](../decisions/ADR-008-token-details-and-revocation.md); originally planned as `tokens_valid_after`, replaced because JWT `iat` has 1-second precision) |
| Lockout / rate limiting | Per-account: after 5 consecutive failures, exponential lock (1 min doubling to 1 h, `locked_until`). Per-IP and per-email limits on `/auth/*` (Redis token bucket later, in-memory in Phase 2). No permanent lockout: that lets attackers lock out victims (DoS) |
| MFA (advanced) | TOTP enrolment with recovery codes; required for platform admins; step-up for sensitive actions (API key creation) |

Why not server-side sessions for the SPA? Sessions are a perfectly good choice (and simpler to revoke). We choose JWT access tokens + rotating refresh tokens deliberately because the project **also** needs token-based auth for the developer platform, and learning token lifecycle (rotation, revocation, key rotation) is a stated goal. This decision is recorded with its trade-offs in [ADR-003](../decisions/ADR-003-authentication-strategy.md).

CSRF: the access token travels in the `Authorization` header (not a cookie), so state-changing API calls are not CSRF-able. The refresh cookie *is* sent automatically, so `/auth/v1/refresh` requires `SameSite=Strict` + an `Origin` check + a custom header (`X-Requested-With`). This is a double defense.

## Customer authentication (Storefront)

- Guest checkout needs no account. The cart is identified by a **cart token** (random, returned once, stored hashed).
- Customer accounts are **per store**. Tokens have `aud=storefront:{storeId}`. A customer token from store A is rejected by store B even if the same email exists in both.

## Developer authentication

- API keys: `sf_live_<prefix>_<secret>`. Lookup by prefix, constant-time hash compare. The key is bound to a store and scopes.
- OAuth access tokens: issued via authorization code + PKCE (Spring Authorization Server), bound to an installation (store + app + scopes). See [12-developer-platform.md](12-developer-platform.md).

## Authorization model

```text
Permission  = resource:action      e.g. products:write, orders:refund, staff:manage
Role        = named set of permissions, per store (Owner, Staff templates + custom)
Membership  = (user, store, role)
Scope       = permission granted to an app (subset of the installing member's permissions)
```

| Level | Rule |
|---|---|
| Platform | `platform_role = ADMIN` → Platform Admin API only. Admins are not implicitly members of stores; store data access goes through audited admin endpoints |
| Store (tenant) | Principal must have an active membership in the path's store. Otherwise **404** (don't reveal the store exists) |
| Permission | Role grants `resource:action`. Checked in the service: `access.require(principal, storeId, ORDERS_REFUND)` |
| Resource ownership | Resource loaded **with** `store_id = :storeId` → it cannot belong to another store. For customers: `order.customerId == principal.customerId` |
| Anti-escalation | A member can grant only permissions they hold; only owners manage owners; the last owner cannot be removed |
| Apps | Effective permission = installation scopes ∩ (for user-delegated tokens) user permissions |

## Common security mistakes (and our guard)

| Mistake | Guard |
|---|---|
| Trusting `storeId` from a header/body | Store comes from the path + membership check, or from the credential |
| Checking auth in the controller only, while jobs and admin call the service directly | Check in the application service |
| `findById(id)` then comparing store afterwards (and forgetting sometimes) | Repositories require `StoreId`; RLS |
| Long-lived JWT with permissions inside | 15 min tokens, permissions looked up |
| `alg: none` / HS256 with a guessable secret / accepting any `kid` | Spring Security Nimbus decoder with a fixed algorithm and a JWKS allow list |
| Storing tokens/API keys in plaintext | Hash (SHA-256 for high-entropy tokens; Argon2 for passwords) |
| Enumeration through different error messages or timing | Uniform responses; dummy hash on unknown email |
| Refresh token in `localStorage` | HttpOnly cookie; access token in memory |
| Logging `Authorization` headers or passwords | Redaction filter + tests |
