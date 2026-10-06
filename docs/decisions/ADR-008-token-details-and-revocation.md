# ADR-008: Access-token format, revocation by security stamp, strict refresh rotation

- **Status:** Accepted (refines [ADR-003](ADR-003-authentication-strategy.md))
- **Date:** 2026-10-06
- **Phase:** P2

## Context
ADR-003 chose short-lived JWT access tokens and rotating opaque refresh tokens. Implementing it raised details that ADR-003 left open or got slightly wrong.

## Problem
1. How do we invalidate *all* of a user's access tokens immediately (password reset, disabled account)?
2. What exactly goes into the token and how is it validated?
3. How strict is refresh-token reuse detection?
4. Where do tokens travel in URLs (email links)?

## Options Considered
For (1):
- **`tokens_valid_after` timestamp** compared to the JWT `iat` (the blueprint's original plan). JWT `iat` has **one-second precision**: a token issued in the same second as the reset is ambiguous. Either old tokens survive, or brand-new ones are rejected. The integration test was inherently flaky.
- **Token blacklist by `jti`.** Precise, but needs a lookup table that grows with every revocation.
- **Security stamp:** an integer on the user, copied into each token (`sstamp` claim), incremented on reset. A token is valid only if its stamp equals the current one. Exact and tiny. This is the same idea as ASP.NET Identity's "security stamp".

For (3): strict (any reuse revokes the family) vs. a short grace period that tolerates two browser tabs refreshing simultaneously.

## Decision
1. **Security stamp.** The per-request user lookup that ADR-003 already accepted (to cut off disabled users) also checks the stamp.
2. **RFC 9068 profile:** header `typ: at+jwt`, ES256 only, claims `iss, sub, aud=merchant, exp, iat, jti, client_id, sstamp`. Validation is pinned to ES256 and our JWKS, and Spring Security's `createAtJwtValidator` checks typ/iss/aud/client_id/timestamps.
3. **Strict reuse detection.** The refresh token is consumed with one conditional `UPDATE ... RETURNING`. Any later presentation of a used token revokes the family. The SPA must serialize refreshes, with one in-flight refresh shared by all callers in a tab.
4. **No secrets in URL paths.** Email links carry the token in the URL **fragment** (`#token=`), which browsers never send to servers. The SPA POSTs it in a JSON body. The endpoints therefore became `POST /auth/v1/email-verifications/confirmation` and `POST /auth/v1/password-resets/confirmation`, not `/{token}` paths.
5. The refresh cookie is scoped to `Path=/auth/v1/token` (refresh at `/token/refresh`, logout at `/token/revocation`). Lockout threshold: **5** consecutive failures (exponential 1 min → 1 h). The per-email rate limit (10/min) sits above it, so lockout is observable before rate limiting kicks in.

## Why We Chose It
Exact revocation semantics without new tables. A standard token profile that prevents token-type confusion. Secrets stay out of access logs.

## Trade-offs
- Strict reuse detection logs users out if two tabs refresh at the same moment and the client doesn't coordinate. Acceptable: the fix belongs in the client, and the security benefit is real.
- Incrementing the stamp kills *all* sessions. There is no per-device revocation of access tokens (refresh tokens can still be revoked per family).

## Consequences
- Blueprint chapters 07/08 were updated to these names and rules.
- The frontend (F1) must implement single-flight refresh.
