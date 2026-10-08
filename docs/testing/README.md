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
- Emails are sent by the job worker: `RecordingEmailSender` drains the queue (`JobWorker.drain()`) before every mailbox read; `mailbox.failNext(n)` simulates SMTP outages. Test-only job handlers live in `TestJobHandlers`.
- Webhook receivers: a static WireMock server (unique path per test) or a raw `ServerSocket` when exact byte timing matters (WireMock's dribble delays the status line too). Tests that move time call `runDueAt(t)` (rounds until idle): one round starts at most 4 deliveries (bulkhead) and the shared database holds other tests' retries.
- Payments use the in-process `FakePaymentProvider` (a stateful Stripe simulator): `PaymentFlows` plays browser, provider and merchant; tests deliver webhooks themselves (`storeforge.payments.fake.webhook-url=` is blank in tests) so they can duplicate, drop, delay or forge them; `provider.failNext(UNAVAILABLE | RESPONSE_LOST)` injects provider failures. The real Stripe adapter is covered by WireMock contract tests instead.
- Platform-wide jobs (sweeper, order expiry) see **all** test stores' data in the shared database: assert on your own store's rows, not on global counts.
- `StoreFlows` creates owners with stores, roles and invited members over HTTP; `CatalogFlows` creates products and stock; `StorefrontFlows` acts as an anonymous shopper (hostname + cart token).
- Scheduling is **off** in tests (`storeforge.scheduling.enabled=false`); tests call background work (e.g. `ReservationSweeper.sweepOnce()`) explicitly.
- Spans are captured by an `InMemorySpanExporter` bean (all ITs); call `SdkTracerProvider.forceFlush()` before asserting. No OTLP export happens in tests (the `otel` profile is off).
- `QueryCounter` (test-only `BeanPostProcessor`) wraps the `DataSource` and counts prepared statements: use it to assert constant query counts for list endpoints.
- Concurrency tests start N threads on a `CountDownLatch` and assert **invariants** (exactly one user, exactly one successful rotation), not just "no exception".

## Current suite (P1–P11)

| Test | Level | Protects |
|---|---|---|
| `RequestIdsTest` | Unit | Header validation incl. log-forging inputs; generated ID format |
| `ModularityTest` | Architecture | Module boundaries, no cycles (Spring Modulith) |
| `DatabaseRolesIT` | Integration | Runtime role is non-owner, cannot DDL (42501), migrations applied by owner, DB-side timeouts present |
| `ErrorResponseIT` | API | Problem Details format, `code`, `requestId`, `traceId`; request-ID propagation/replacement; opaque 500 logged as JSON with request and trace ids |
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
| `CartIT` | API | Increment on re-add, live prices, quantity limits, token required, no cross-store carts, only sellable items, `no-store` |
| `CheckoutIT` | API + integration | Completion reserves stock, freezes prices (unchanged after a catalog edit), records the customer, closes the cart; `total_changed` reserves nothing; insufficient stock leaves the checkout OPEN; contact details required; one customer per email |
| `CheckoutIdempotencyIT` | Concurrency + security | **20 concurrent same-key retries → one execution, identical bodies**; different keys → one completion; key reuse → 422 without PII; failed attempts not stored (mutation-checked, EXP-006) |
| `OrderStateMachineTest` | Unit | Every legal and illegal order transition, expiry idempotency |
| `OrderPlacementIT` | API + concurrency | Order from checkout snapshot, per-store numbering, **12 concurrent orders → #1001..#1012**, immutable lines |
| `OrderLifecycleIT` | API + concurrency | Pay → stock sold → fulfil → closed; cancel releases; illegal transitions; **pay vs. cancel race ×5**; expiry job; filters/search; read-only role |
| `BackoffTest` | Unit | Exponential growth, cap, no overflow, jitter range |
| `JobQueueIT` | Integration + concurrency | Retry timeline (controlled time), DEAD + operator requeue, permanent failure, lease reclaim after a simulated crash, **4 workers × 60 jobs → each once**, WARN/ERROR lines actually emitted |
| `OutboxIT` | Integration | Rolled-back transaction → no event; no transaction → refused; relay re-run → no duplicate jobs; **job span in the request's trace** |
| `EndpointPolicyTest`, `SecretBoxTest`, `RetryPolicyTest` | Unit | SSRF rules on resolved addresses (29 cases incl. encodings, rebinding); AES-GCM tamper/row binding; retry schedule |
| `WebhookDeliveryIT` | Integration (WireMock + raw socket receivers) | Signed deliveries verified independently; idempotent fan-out; retries with identical bytes; slow and **dripping** receivers; redirects not followed; 429/4xx; replay; pause + bulk replay; rotation overlap; destination turned private at delivery |
| `StripeFormatTest`, `PaymentTest` | Unit | Webhook signature rules (independent HMAC), event parsing; payment state machine and refund arithmetic |
| `StripePaymentProviderContractTest` | Contract (WireMock) | Stripe request shape; error classification incl. **read timeout = unknown**, **refused connection = not performed** |
| `CardPaymentIT` | Integration + concurrency | Card payment end to end; concurrent starts → one intent; lost response → safe retry; declines; duplicate/concurrent/re-sent webhooks applied once; forged/replayed webhooks rejected |
| `PaymentRecoveryIT` | Integration | Lost webhook → reconciliation; poisoned item isolation; pay-vs-expire race → automatic refund; cancellation voids the intent; paid twice → refund; circuit breaker open/fast-fail/recover |
| `RefundIT` | Integration + concurrency | Partial/full refunds; idempotency; **10 concurrent refunds never exceed the capture**; lost refund response completed by the job; pending refunds via webhook; permissions |
| `ReliableEffectsIT` | Integration | **SMTP outage → delivered after retry**, sensitive body erased; redelivered events → one email, stats once (inbox); housekeeping |
| `TracingIT` | Observability | One trace per request with JDBC `query` spans and the `storeforge.store_id` attribute; `inventory.reserve` parents its SQL spans (in-memory span exporter) |
| `StorefrontCatalogIT` | API + performance | Hostname → store, only ACTIVE, no internal fields, per-store handles, unknown host, **N+1 detector** (`QueryCounter`) |

**Mutation testing by hand:** removing the store row lock makes the write-skew test fail 5/5 ([EXP-003](../experiments/EXP-003-write-skew-last-owner.md)). Do this whenever a test guards a concurrency mechanism: a test that passes without the mechanism proves nothing.
