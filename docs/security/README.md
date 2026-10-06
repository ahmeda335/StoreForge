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

## Not yet (by design)
Tenant authorization / RBAC (P3), MFA (P14), dependency and secret scanning in CI (P14), TLS (P16 via the reverse proxy).

## Accepted risk
Actuator health shows component details including exception class names. This is acceptable only because the management port is private.
