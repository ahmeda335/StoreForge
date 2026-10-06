# Progress

| Phase | Status | Summary |
|---|---|---|
| P0 Blueprint | ✅ Done (2026-10-06) | [blueprint/](blueprint/README.md), ADR-001…005 |
| P1 Foundation | ✅ Done (2026-10-06) | See below |
| P2 Identity & authentication | ✅ Done (2026-10-06) | See below |
| P3 Stores, tenancy & authorization | ⏭ Next | Study list below |
| P4–P18 | Planned | [Roadmap](blueprint/30-roadmap.md) |

## P1: Foundation (as built)

**What exists**
- Spring Boot 4.0 / Java 21 application, one module so far: `shared` (kernel, OPEN module).
- `RequestIdFilter`: every request gets a validated or generated `X-Request-Id`, in MDC/logs and the response header.
- `ApiExceptionHandler`: all errors are RFC 9457 Problem Details with `code` + `requestId`; unexpected errors become opaque 500s and are logged once at ERROR.
- PostgreSQL 17 with two roles: `storeforge_owner` (Flyway) and `storeforge_app` (runtime DML, 10s statement timeout, 30s idle-in-transaction timeout). Schema `storeforge`. Migration `V1` sets default privileges.
- Actuator on management port 8081: liveness (process only), readiness (process + DB), Prometheus metrics with HTTP histograms and Hikari pool metrics.
- JSON (ECS) structured logs on stdout including `requestId`, `service.version` and `service.environment`.
- Graceful shutdown (20s per phase), virtual threads enabled.
- Docker: multi-stage image (non-root, layered, JRE-only); Compose with PostgreSQL and an optional `app` profile.
- CI: GitHub Actions, `./mvnw verify` + Docker image build.

**Tests (24)**
- Unit: `RequestIdsTest` (11 cases incl. log-forging inputs), `ModularityTest` (Spring Modulith verification).
- Integration (Testcontainers PostgreSQL, real roles): `DatabaseRolesIT` (runs as app role, cannot DDL [42501], migrations by owner, DB-side timeouts), `ErrorResponseIT` (404 problem format, request-id propagation/replacement, 500 leaks nothing but is logged with the request id), `OperationalEndpointsIT` (liveness excludes DB, readiness includes DB, Prometheus content, actuator not on public port).

**Verified manually** ([experiment write-up](experiments/EXP-001-database-unavailable-p1.md)): DB stopped → readiness 503 in about 3s, liveness 200; DB restarted → readiness UP within about 1s; app started with DB down → exits with code 1 in about 3s; `docker stop` → graceful shutdown logged.

**Known limitations (intentional; see "Not yet")**
- No business modules, no authentication. Every endpoint is a 404 problem.
- No trace IDs in logs yet (OpenTelemetry arrives in P6). The request ID is the only correlation key.
- Actuator health shows details (including exception class names) because it is only on the private management port. Restrict this if the port is ever exposed.
- The CI workflow has not run yet: the repository has no GitHub remote.

**Study before P2 (historical):** password hashing (Argon2id/BCrypt, salts, work factors); JWT structure and validation pitfalls (RFC 8725: `alg` pinning, `aud`/`iss`/`exp`); Spring Security filter chain and `SecurityContext`; cookies (`HttpOnly`, `Secure`, `SameSite`), CSRF vs. bearer tokens; JPA persistence context basics (JPA is introduced in P2). See [learning map](blueprint/27-learning-map.md).

## P2: Identity & authentication (as built)

**What exists**
- `identity` module: registration (enumeration-resistant), email verification, login with Argon2id, ES256 RFC 9068 access tokens (15 min), opaque rotating refresh tokens in an HttpOnly/SameSite=Strict cookie with family revocation on reuse, logout, password reset (revokes all sessions via security stamp), JWKS endpoint, `GET /admin/v1/me`.
- `notifications` module: `EmailSender` port + SMTP adapter; Mailpit in Compose.
- Shared kernel: UUIDv7 ids, `StoreForgeException` → Problem Details (422 field errors), `RateLimiter` (Bucket4j + Caffeine), `ConstraintViolations`, injectable `Clock`.
- Security chains per API (`/auth/**` public, `/admin/**` bearer JWT, fallback), Problem Details 401/403.
- Migration V2: `users` (citext email), `refresh_tokens`, `email_tokens`.
- Design refinements in [ADR-008](decisions/ADR-008-token-details-and-revocation.md): security stamp instead of `tokens_valid_after`, RFC 9068 tokens, strict rotation, tokens only in URL fragments/bodies, lockout after 5 failures.

**Tests: 70 total** (28 unit + 42 integration). New: registration races (8 threads → 1 user), refresh races (8 threads → 1 success), forged-token matrix (9 cases), reset revocation, lockout and 429, secrets-never-logged scan.

**Verified manually:** full flow against real SMTP (Mailpit): register → email link → verify → login (cookie flags, JWT header/claims inspected) → `/admin/v1/me` → refresh → JWKS. [EXP-002](experiments/EXP-002-email-provider-unavailable.md): registration while SMTP is down → 202, WARN without secrets, failed-email metric.

**Known limitations**
- Emails: synchronous after commit, lost on failure (P9 outbox).
- Rate limits: per instance (P12 Redis). The proxy must overwrite `X-Forwarded-For` (P16).
- Ephemeral signing key by default locally. Production config is P16.
- Strict reuse detection needs single-flight refresh in the SPA (F1).
- No MFA, no breached-password check (P14). No CORS configuration yet (added with the SPA in F1).

**Study before P3:** PostgreSQL row-level security (`CREATE POLICY`, `USING` vs. `WITH CHECK`, `ENABLE`/`FORCE ROW LEVEL SECURITY`, why owners bypass it); `SET LOCAL` vs. `SET` and why it matters with connection pools; RBAC modelling and privilege escalation; the write-skew anomaly ("≥1 owner" invariant) and how row locks prevent it; blueprint [04-multi-tenancy](blueprint/04-multi-tenancy.md).
