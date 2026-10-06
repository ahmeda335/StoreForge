# Refresh Tokens, Cookies and CSRF

**Problem.** Access tokens must be short-lived (theft window, revocation), but users shouldn't log in every 15 minutes. Where the long-lived credential lives decides which attacks work: `localStorage` is readable by any XSS, and cookies are sent automatically, which enables CSRF.

**Why we need it.** Long sessions for merchants without long-lived bearer tokens in JavaScript.

**Alternatives.** Long-lived access tokens (bad); refresh token in `localStorage` (XSS-stealable); server sessions with a cookie (needs CSRF tokens on all endpoints); BFF pattern (backend-for-frontend holds tokens server-side; strong, more infrastructure).

**Decision.** Opaque refresh token in an `HttpOnly; Secure; SameSite=Strict; Path=/auth/v1/token` cookie, rotated on every use, stored hashed, grouped in families with reuse detection ([ADR-008](../decisions/ADR-008-token-details-and-revocation.md)).

**Mental model.**
- **Rotation:** each refresh returns a new token and burns the old one. A stolen token then stays useful only until the next legitimate refresh, and if the thief uses it first, the legitimate client's next refresh presents a *used* token. That **reuse** is the theft signal, and the whole family is revoked.
- **Atomic consumption:** `UPDATE ... SET used_at = now WHERE token_hash = ? AND used_at IS NULL ... RETURNING`. Under concurrency PostgreSQL row-locks the row, and the second statement re-checks `used_at IS NULL` after the first commits. Exactly one wins (tested with 8 threads).
- **Transaction boundaries differ by branch:** rotate = one transaction (consume + insert). Reuse revocation commits on its own, because the request then fails with 401, and a surrounding `@Transactional` would roll the revocation back.
- **CSRF:** cookies attach to cross-site requests automatically. Defenses on the two cookie endpoints: `SameSite=Strict` (not sent cross-site), a required custom header (a cross-site form can't set it, and a cross-site `fetch` with it needs a CORS preflight we don't grant), and an `Origin` allow-list. API calls use the `Authorization` header, which browsers never add on their own, so they are not CSRF-able at all.

**Project usage.** `TokenService`, `RefreshTokenStore`, `RefreshTokenCookies`, `AuthController.refresh/logout`.

**Failure cases.** Two tabs refreshing at once look like reuse and log the user out. The client must single-flight refreshes. Clock issues on `expires_at`. Cookie `Path` too broad would send the token to every endpoint (more exposure).

**Testing.** `TokenRefreshIT`: rotation, reuse revokes the family, 8 concurrent refreshes → exactly one success, logout, missing header / foreign origin → 403 without consuming the token.

**Observability.** `storeforge_auth_token_refreshes_total{outcome}`. WARN on reuse with `userId`/`familyId`. Runbook: [auth-anomalies](../operations/runbooks/auth-anomalies.md).

**When NOT to use it.** Machine clients (no browser): use client credentials / API keys (P12).
