# ADR-003: JWT access tokens + rotating opaque refresh tokens (self-issued)

- **Status:** Accepted, refined by [ADR-008](ADR-008-token-details-and-revocation.md) (security stamp instead of `tokens_valid_after`)
- **Date:** 2026-10-06
- **Phase:** P0 (implementation in P2)

## Context
Humans (merchants, staff, customers, admins) use SPAs; machines (apps) use API keys and OAuth tokens. Learning token lifecycle is an explicit goal.

## Problem
How do browser clients authenticate, and who issues credentials?

## Options Considered
1. **Server-side sessions** (Spring Session in PG/Redis, cookie). Pros: trivial revocation, simple. Cons: CSRF protection required for all cookie-authenticated calls; doesn't exercise token concepts needed for the developer platform anyway.
2. **External IdP** (Keycloak/Auth0). Pros: production-grade, MFA included. Cons: hides exactly what we want to learn; adds a heavy dependency.
3. **Self-issued short-lived JWT access tokens (ES256, 15 min) + opaque refresh tokens (hashed in DB, rotated, reuse detection), refresh token in an HttpOnly SameSite=Strict cookie, access token in memory.**

## Decision
Option 3 for humans. Opaque, DB-checked tokens for apps (ADR to come in P12).

## Why We Chose It
- Stateless verification on the hot path; revocation handled by short TTL + refresh-token revocation + `tokens_valid_after`.
- Teaches signing keys, rotation (`kid`, JWKS), refresh rotation and reuse detection.
- Bearer header avoids CSRF on API calls; only the refresh endpoint is cookie-based and hardened.

## Trade-offs
- Up to 15 minutes in which a revoked user's access token still works (unless `tokens_valid_after` check is hit).
- More moving parts than sessions; easy to misuse (mitigated by RFC 8725 rules and forged-token tests).
- Self-built login means owning password storage and reset flows correctly.

## Consequences
- Permissions are **not** embedded in tokens; looked up per request (short local cache).
- Separate audiences: `merchant`, `storefront:{storeId}`, `platform`.
- Signing keys provided via configuration/secret store; never generated at random per instance in prod.
