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
- Each test uses unique emails (`uniqueEmail()`) and stores (`StoreFlows.uniqueHandle()`), because the context, database and rate-limiter buckets are shared across test classes.
- `StoreFlows` creates owners with stores, roles and invited members over HTTP; `CatalogFlows` creates products.
- Scheduling is **off** in tests (`storeforge.scheduling.enabled=false`); tests call background work (e.g. `ReservationSweeper.sweepOnce()`) explicitly.
- `QueryCounter` (test-only `BeanPostProcessor`) wraps the `DataSource` and counts prepared statements: use it to assert constant query counts for list endpoints.
- Concurrency tests start N threads on a `CountDownLatch` and assert **invariants** (exactly one user, exactly one successful rotation), not just "no exception".

## Current suite (P1–P5)

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
| `StoreLifecycleIT` | API | Store creation (owner, roles, Location), handle uniqueness/reserved/format, audited rename, 401 |
| `StaffManagementIT` | Security + concurrency | Invitation flow, invitation bound to email & single-use, anti-escalation matrix, per-endpoint permissions, last owner, **concurrent mutual demotion (write skew)**, role deletion rules |
| `TenantIsolationIT` | Security | **Every** `{storeId}` endpoint (discovered from the handler registry) returns 404 for other stores; other stores' role/member ids are 404 inside my store |
| `RowLevelSecurityIT` | Integration (DB) | RLS meta-test, no-context = no rows, cross-tenant read/write blocked, context ends with the transaction on a pooled connection, no tenant switching, audit append-only |
| `AuditLogIT` | API | Keyset pagination: newest first, no gaps/duplicates, `hasMore`, invalid cursor |
| `MoneyTest` | Unit | Minor units per currency, strict parsing, exact arithmetic, currency mismatch |
| `ProductAdminIT` | API + concurrency | Product creation and money, aggregate rules → precise error codes, If-Match 412, **6 concurrent writers → one winner**, variant lifecycle bumps the version, filters/sort/pagination, read-only role |
| `InventoryAdminIT` | API | Adjustments + ledger, never below reserved, reason allow-list, foreign/unknown variants, storefront availability (incl. untracked) |
| `InventoryReservationIT` | Integration + concurrency | Commit/release semantics, all-or-nothing, idempotency, **3 concurrent sweepers expire each reservation once** |
| `InventoryConcurrencyIT` | Concurrency | **100 buyers / 1 unit (×3)**, 80 / 50, opposite lock orders (no deadlock), ledger == levels |
| `OversellExperimentIT` | Experiment | EXP-004: naive oversells (10/50), SERIALIZABLE aborts, conditional update exactly 1 |
| `StorefrontCatalogIT` | API + performance | Hostname → store, only ACTIVE, no internal fields, per-store handles, unknown host, **N+1 detector** (`QueryCounter`) |

**Mutation testing by hand:** removing the store row lock makes the write-skew test fail 5/5 ([EXP-003](../experiments/EXP-003-write-skew-last-owner.md)). Do this whenever a test guards a concurrency mechanism: a test that passes without the mechanism proves nothing.
