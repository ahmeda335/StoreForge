# Testing (as built)

Strategy: [blueprint/17-testing.md](../blueprint/17-testing.md).

## Running

| Command | Runs | Needs Docker |
|---|---|---|
| `./mvnw test` | Unit tests (`*Test`, surefire) + module boundary check | No |
| `./mvnw verify` | Above + integration tests (`*IT`, failsafe) | Yes |
| `./mvnw verify -Dit.test=DatabaseRolesIT` | One integration test class | Yes |

## Conventions

- `*Test` = no Spring context, no I/O, milliseconds. `*IT` = full context + real PostgreSQL.
- Integration tests extend `support.IntegrationTest`: random HTTP and management ports, PostgreSQL via `support.PostgresTestDatabase`.
- **One PostgreSQL container per JVM** (singleton pattern): started once in a static initializer and reused by all IT classes. Spring also caches the application context across classes with identical configuration.
- **Never H2.** The container is provisioned with the same role script as Compose, and tests run as `storeforge_app`, so privilege mistakes fail in tests, not in production.
- HTTP assertions go through `support.HttpTestClient` (`java.net.http`), a black-box client hitting the real server, so filters, headers and content types are all exercised.
- Assert on stable contracts (`code`, status, headers), not human text.
- Emails are captured by `RecordingEmailSender` (replaces the SMTP adapter in all ITs); `AuthFlows` bundles register/verify/login steps.
- Each test uses unique emails (`uniqueEmail()`), because the context, database and rate-limiter buckets are shared across test classes.
- Concurrency tests start N threads on a `CountDownLatch` and assert **invariants** (exactly one user, exactly one successful rotation), not just "no exception".

## Current suite (P1–P2)

| Test | Level | Protects |
|---|---|---|
| `RequestIdsTest` | Unit | Header validation incl. log-forging inputs; generated ID format |
| `ModularityTest` | Architecture | Module boundaries, no cycles (Spring Modulith) |
| `DatabaseRolesIT` | Integration | Runtime role is non-owner, cannot DDL (42501), migrations applied by owner, DB-side timeouts present |
| `ErrorResponseIT` | API | Problem Details format, `code`, `requestId`; request-ID propagation/replacement; opaque 500 logged as JSON with the request ID |
| `OperationalEndpointsIT` | API | Liveness excludes DB; readiness includes DB; Prometheus metrics present; actuator not on the public port |
| `IdsTest`, `RateLimiterTest`, `EmailAddressTest` | Unit | UUIDv7 layout/order; token bucket + Retry-After; email normalization/validation |
| `RegistrationIT` | API + concurrency | Verify-before-login, single-use links, enumeration-identical responses, normalization, **8 concurrent registrations → 1 user**, 422 field errors, unknown properties → 400 |
| `LoginIT` | Security | Uniform failures, lockout after 5, per-email 429 with Retry-After |
| `TokenRefreshIT` | Security + concurrency | Rotation, reuse → family revoked, **8 concurrent refreshes → exactly one success**, logout, CSRF header/Origin checks |
| `AccessTokenSecurityIT` | Security | Forged-token matrix (alg none, foreign key, HS256 confusion, expired, wrong aud/iss/typ, stale stamp, tampered); sanity check that a correctly signed token IS accepted |
| `PasswordResetIT` | API | Reset revokes access + refresh tokens; single-use; newest link only; unknown email silent |
| `SensitiveDataLoggingIT` | Security | No password/token/link in any output, including failure paths |
