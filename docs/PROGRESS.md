# Progress

| Phase | Status | Summary |
|---|---|---|
| P0 Blueprint | ✅ Done (2026-10-06) | [blueprint/](blueprint/README.md), ADR-001…005 |
| P1 Foundation | ✅ Done (2026-10-06) | See below |
| P2 Identity & authentication | ✅ Done (2026-10-06) | See below |
| P3 Stores, tenancy & authorization | ✅ Done (2026-10-06) | See below |
| P4 Catalog | ✅ Done (2026-10-06) | See below |
| P5 Inventory & concurrency | ✅ Done (2026-10-06) | See below |
| P6 Observability stack & tracing | ✅ Done (2026-10-06) | See below |
| P7 Customers, cart & checkout | ✅ Done (2026-10-06) | See below |
| P8 Orders | ✅ Done (2026-10-06) | See below |
| P9 Outbox, jobs & notifications | ✅ Done (2026-10-07) | See below |
| P10 Payments | ✅ Done (2026-10-07) | See below |
| P11 Outbound webhooks | ✅ Done (2026-10-08) | See below |
| P12 Developer platform (+ Redis) | ✅ Done (2026-10-08) | See below |
| P13 Commerce extensions | ✅ Done (2026-10-08) | See below |
| P14 Security hardening | ✅ Done (2026-10-09) | See below |
| P15 Load testing & performance | ✅ Done (2026-10-10) | See below |
| P16 Production infrastructure & CD | ✅ Done (2026-10-10) | See below |
| P17 Failure experiment suite | ⏭ Next | Study list below |
| P18 | Planned | [Roadmap](blueprint/30-roadmap.md) |

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

**Study before P3 (historical):** PostgreSQL row-level security (`CREATE POLICY`, `USING` vs. `WITH CHECK`, `ENABLE`/`FORCE ROW LEVEL SECURITY`, why owners bypass it); `SET LOCAL` vs. `SET` and why it matters with connection pools; RBAC modelling and privilege escalation; the write-skew anomaly ("≥1 owner" invariant) and how row locks prevent it; blueprint [04-multi-tenancy](blueprint/04-multi-tenancy.md).

## P3: Stores, tenancy & authorization (as built)

**What exists**
- `stores` module: create store (atomic provisioning: store, platform domain, Owner + Staff roles, owner membership, audit), my stores, get/rename, members (list/change role/remove/leave), roles (list/create/delete), invitations (invite/list/revoke/accept), audit log endpoint. `StoreAccess` is the authorization decision point for all future tenant modules.
- `audit` module: append-only audit log written inside business transactions, keyset-paginated.
- Tenancy kernel: `StoreId`, `TenantTransactions` (transaction-local `set_config`), RLS function and policies (V3), keyset `Cursor`/`Page`.
- Decisions: [ADR-009](decisions/ADR-009-tenant-context-and-directory-tables.md) (directory vs. data tables, explicit tenant transactions, 404 for non-members).

**Tests: 93 total** (28 unit + 65 integration). New: tenant isolation suite over auto-discovered endpoints, RLS meta-test and behaviour tests (including a pooled-connection context-leak test), anti-escalation matrix, concurrent mutual demotion, audit pagination.

**Verified:** the write-skew mutation experiment ([EXP-003](experiments/EXP-003-write-skew-last-owner.md)): removing the lock fails the concurrency test 5/5.

**Known limitations**
- Membership is looked up per request (no cache); one extra `set_config` round trip per tenant transaction (measure in P15).
- No store suspension endpoints (P14), no custom domains or storefront resolution yet (P4), no store-creation quota.
- Invitation emails are not durable (P9).
- No ArchUnit rule yet enforcing `StoreId` parameters on tenant repositories (RLS + isolation suite cover the risk). Revisit in P4 when the first large tenant module lands.

**Study before P4 (historical):** aggregate design (product with variants and options as one consistency boundary); optimistic locking with `@Version` and HTTP `ETag`/`If-Match` (412 vs. 409); JPA fetching (N+1, `@EntityGraph`, DTO projections) and how to assert query counts in tests; B-tree composite indexes for `WHERE store_id = ? AND status = ? ORDER BY created_at DESC, id DESC`; PostgreSQL `NULLS NOT DISTINCT` unique constraints (variant option combinations).

## P4: Catalog (as built)

**What exists**
- `catalog` module: Product aggregate (options, 1–100 variants, money in minor units), admin API (create/list/get/update, variant add/update/remove) with ETag/If-Match, public storefront API (list, by handle) resolved from the hostname.
- `stores`: `StoreDirectory` (cached host → store, 60s, negative caching), `@CurrentStorefront`, a `/storefront/**` security chain.
- Shared: `Money`, `InvalidParameterException`, 409 `concurrent_modification` mapping.
- V4 migration with RLS, composite FK, partial unique SKU index, `NULLS NOT DISTINCT` option combinations.
- [ADR-010](decisions/ADR-010-catalog-persistence-and-concurrency.md): JPA for aggregates, SQL read models for lists, HTTP optimistic concurrency.

**Tests: 113 total** (37 unit + 76 integration). New: aggregate rule matrix, If-Match 412, 6 concurrent writers → one winner, storefront isolation by hostname, **N+1 detector** (mutation-tested: 9 vs. 18 statements).

**Known limitations:** `ILIKE` search (FTS in P13); no images/collections; no stock on the storefront (P5); no storefront caching or rate limiting (P12/P13); 9 statements per storefront list request (investigate in P15).

**Study before P5 (historical):** PostgreSQL MVCC and READ COMMITTED re-check behaviour on UPDATE (docs ch. 13.2); lost update vs. write skew (recap EXP-003); `SELECT ... FOR UPDATE` vs. conditional `UPDATE ... WHERE available >= :q`; deadlocks and why lock ordering prevents them (`40P01`); `FOR UPDATE SKIP LOCKED` for sweepers; append-only ledgers and reconciliation; [ADR-004](decisions/ADR-004-inventory-concurrency.md).

## P5: Inventory & concurrency (as built)

**What exists**
- `inventory` module: lazily created stock items, adjustments (never below reserved) with an append-only ledger, reservations (all-or-nothing, idempotent per checkout, deterministic lock order), commit/release, a `@Scheduled` expiry sweeper with `FOR UPDATE SKIP LOCKED`, storefront availability.
- V5 migration; the `storeforge_tenant_scanner` role and a store-id-only SECURITY DEFINER function ([ADR-011](decisions/ADR-011-cross-tenant-background-work.md)).
- Scheduling switch (`storeforge.scheduling.enabled`); `INVENTORY_SWEEPER_INTERVAL`.

**Tests: 130 total** (37 unit + 93 integration). New: 100-buyer last-item race (repeated), opposite-order deadlock test, concurrent sweepers, ledger reconciliation, and the [EXP-004](experiments/EXP-004-oversell-three-ways.md) experiment (naive oversold 10× = pool size; SERIALIZABLE 9 aborts; conditional update exactly 1).

**Verified manually:** fresh Compose provisioning + V1–V5 migrations; definer function owned by the NOLOGIN BYPASSRLS role; `SET ROLE storeforge_tenant_scanner` as the app role → permission denied; sweeper running every 5s without errors.

**Known limitations:** no HTTP reservation path until checkout (P7); single location; hot-item throughput unmeasured (P15). **Existing local volumes must be re-created** (`docker compose down -v`) for the new role.

**Study before P6 (historical):** the three signals and when each answers a question; OpenTelemetry concepts (API vs. SDK, resource, span, context propagation, OTLP, Collector); W3C `traceparent`; Micrometer Observation API and how Spring Boot 4 bridges it to OTel; Prometheus data model (labels, cardinality), counters vs. gauges vs. histograms and `rate()`/`histogram_quantile()`; exemplars (metric → trace links); head vs. tail sampling.

## P6: Observability stack & tracing (as built)

**What exists**
- OpenTelemetry via `spring-boot-starter-opentelemetry`: traces (HTTP, Spring Security, JDBC via `datasource-micrometer`, scheduled tasks), OTLP metrics (10s), OTLP logs via the logback appender. All export is off unless `SPRING_PROFILES_ACTIVE=otel`.
- Correlation: `X-Trace-Id` response header, `traceId` in problem details, `traceId`/`spanId` in JSON logs, `storeforge.store_id` span attribute, MDC ids as Loki structured metadata.
- Custom Observation `inventory.reserve` (low- vs. high-cardinality key values).
- Compose profile `observability` (`grafana/otel-lgtm:0.35.0`) with the provisioned **StoreForge Overview** dashboard (RED, pool, business counters, warn/error logs).
- [ADR-012](decisions/ADR-012-observability-pipeline.md).

**Tests: 132 total** (37 unit + 95 integration). New: `TracingIT` (span tree, JDBC children, tenant attribute, business observation parenting SQL); `ErrorResponseIT` checks problem `traceId` = header = log line.

**Verified manually against the running stack:** an inventory adjustment produced one 18-span trace in Tempo (`http post …` → security spans → `connection`/`query`); TraceQL `{ span.storeforge.store_id = "…" }` found the store's 20 traces; Prometheus received `http_server_requests_milliseconds_*`, Hikari and `storeforge_*` series, with p95 per route (login ≈ 89 ms, from Argon2, as expected); Loki received logs whose `trace_id` matched the 404 response's `X-Trace-Id`; the dashboard was provisioned with 12 panels. [EXP-005](experiments/EXP-005-collector-unavailable.md) recorded a Collector restart during export.

**Known limitations:** no alerts/SLOs yet (P15/P16); exemplars not verified; telemetry is not persisted across `otel-lgtm` restarts; OTLP exporter failures log at ERROR (noise, EXP-005); async propagation comes with jobs (P9).

**Study before P7 (historical):** idempotency keys (Stripe's design: key + request fingerprint + stored response; in-flight vs. completed; scope and TTL); HTTP method semantics (safe vs. idempotent); bearer secrets for anonymous carts (why hash the cart token); price snapshotting (cart = live prices, checkout = frozen totals); money rounding for line totals; how checkout will call `Inventory.reserve` inside one transaction; customer accounts scoped per store (a separate audience from merchant tokens: blueprint 07).

## P7: Customers, cart & checkout (as built)

**What exists**
- `checkout` module: anonymous carts (hashed bearer token, live pricing, limits), checkout sessions (email, shipping address, live totals), completion (one transaction: lock → price from catalog → `expectedTotal` guard → reserve stock → upsert customer → freeze lines/totals → close cart), `checkout.complete` Observation.
- `customers` module: store-scoped guest customers (race-free upsert), admin list/get.
- `catalog`: first public API `VariantLookup`.
- Shared `Idempotency`: required `Idempotency-Key` on completion, stored responses, fingerprint incl. caller identity, `Idempotent-Replayed` header.
- V6 migration; [ADR-013](decisions/ADR-013-checkout-and-idempotency.md); [EXP-006](experiments/EXP-006-duplicate-checkout-completion.md).

**Tests: 148 total** (37 unit + 111 integration). New: `CartIT` (5), `CheckoutIT` (6), `CheckoutIdempotencyIT` (5): 20 concurrent same-key retries, cross-shopper key reuse, retry after failure. Mutation-checked.

**Verified manually:** the full shopper flow over HTTP against the Compose database (V6 applied to the existing volume): cart `no-store`, checkout total 49.00, completion `200 replayed=false`, identical retry `200 replayed=true`, stock 4 with 2 reserved, one customer, zero ERROR log lines.

**Known limitations:** no orders yet (P8); shipping/tax are 0 (P13); no storefront customer accounts; no cleanup of abandoned carts/idempotency keys (P9 jobs); no storefront rate limiting (P12); single-transaction idempotency must be upgraded to an IN_PROGRESS model for payments (P10).

**Study before P8 (historical):** order state machines (financial vs. fulfillment status, illegal transitions); per-store human-friendly order numbers without gaps-vs-performance surprises (counter row with `UPDATE ... RETURNING` vs. sequences, why sequences can't be per tenant easily); compensation (cancel → release stock); what "PENDING_PAYMENT" means before payments exist; snapshotting addresses and lines on the order; how completion will create the order inside the same transaction; merchant order list read models.

## P8: Orders (as built)

**What exists**
- `orders` module: Order aggregate with an orthogonal state machine (status / financial / fulfillment), placement inside checkout completion, gapless per-store numbers from #1001, immutable lines and timeline, manual payment (sells stock), fulfillment, cancellation (releases stock), `UnpaidOrderExpiry` job (payment window 15 min), admin list/detail API.
- The order owns the payment deadline: reservations hold stock for window + grace.
- V7 migration with CHECK constraints for forbidden state combinations; [ADR-014](decisions/ADR-014-orders-lifecycle.md); [EXP-007](experiments/EXP-007-pay-vs-cancel-race.md).

**Tests: 163 total** (41 unit + 122 integration). New: `OrderStateMachineTest` (4), `OrderPlacementIT` (4: 12 concurrent orders → consecutive numbers), `OrderLifecycleIT` (7: pay/cancel race, expiry). The isolation suite flagged the new endpoints until sample bodies were added (it works as intended).

**Verified manually:** V7 applied to the existing Compose volume; storefront purchase → order #1001 → manual payment (stock 4→2, reserved 0) → fulfillment (CLOSED, timeline PLACED/PAID/FULFILLED); zero ERROR lines.

**Known limitations:** no payments/refunds (P10); no partial fulfillment/shipments (P13); no events/emails to the outside (P9); customers can't view orders; the per-store counter row is a hot spot under flash sales (measure in P15).

**Study before P9 (historical):** the dual-write problem (DB + message/email in two systems); transactional outbox; at-least-once delivery and why consumers must be idempotent (inbox / processed-message table); job queues on PostgreSQL (`FOR UPDATE SKIP LOCKED`, leases/visibility timeouts, poison messages, dead-lettering); exponential backoff with jitter; propagating trace context and tenant context through a queue; [blueprint 09](blueprint/09-events-async.md) and [ADR-005](decisions/ADR-005-async-processing.md).

## Between P8 and P9: Lombok (2026-10-07)

On request, boilerplate was replaced with an allow-list of Lombok features ([ADR-015](decisions/ADR-015-lombok.md), [learning note](learning/lombok.md)): 74 files, about 380 fewer lines; `@RequiredArgsConstructor` on ~60 beans, `@Slf4j`, fluent `@Getter` on entities/exceptions, `@NoArgsConstructor(access = PROTECTED)` on entities. No behaviour change: all 163 tests pass unchanged. All future code follows the same rules.

## P9: Outbox, jobs & notifications (as built)

**What exists**
- `jobs` module: transactional `Outbox`, `OutboxRelay` (one transaction, dedup), `JobWorker` (SKIP LOCKED claims, leases, backoff + jitter, DEAD, trace continuation), `Inbox`, queue gauges, `/actuator/jobs` (overview + requeue), `Housekeeping` (incl. cross-tenant idempotency-key purge).
- Durable email: `Notifications.enqueueEmail` in the business transaction, `EmailSendJob` with retries, sensitive bodies erased after delivery. Identity and invitation emails switched over (P2's "lost on SMTP failure" limitation is gone).
- Orders publish `OrderPlaced/Paid/Cancelled/Fulfilled`; consumers: order confirmation email (dedup per order), customer purchase stats (inbox).
- V8 migration; [ADR-016](decisions/ADR-016-async-infrastructure.md); event catalog with explicit delivery semantics in [events/README.md](events/README.md).

**Tests: 178 total** (43 unit + 135 integration). New: `BackoffTest`, `JobQueueIT` (6), `OutboxIT` (4), `ReliableEffectsIT` (4). Mutation-checked ([EXP-008](experiments/EXP-008-async-guarantees-mutations.md)).

**Verified manually** ([EXP-009](experiments/EXP-009-smtp-outage-and-dropped-logs.md)): async verification email in 0.5s; order #1001 confirmation email in Mailpit; customer stats after payment; SMTP stopped during registration → 202, email delivered automatically 10s after Mailpit came back. **Bug found and fixed:** retry/DEAD log lines were silently dropped by the JSON encoder (duplicate MDC key); now covered by tests.

**Known limitations:** single-threaded worker per instance; no ordering or per-tenant fairness in the queue; at-least-once email (rare duplicate after a crash); polling latency ~0.5s; no broker (only when measured, P18).

**Study before P10 (historical):** card payment flow (authorization vs. capture, 3-D Secure / client-side confirmation, why the server never sees card numbers: PCI DSS SAQ-A); Stripe PaymentIntents and their statuses; the "unknown outcome" of a timed-out provider call and idempotency keys toward providers; verifying webhook signatures (HMAC over the raw body, timestamp tolerance, replay); an IN_PROGRESS idempotency model for external calls (ADR-013, EXP-007); Resilience4j timeouts/circuit breakers; WireMock for contract tests; [blueprint 10](blueprint/10-payments.md).

## P10: Payments (as built)

**What exists**
- `payments` module behind a provider port:
  - `FakePaymentProvider`: a stateful in-process Stripe simulator with signed webhooks, test cards and failure injection (default locally and in tests);
  - `StripePaymentProvider`: plain HTTP with explicit timeouts and error classification, pinned by WireMock contract tests;
  - `PaymentGateway`: Resilience4j circuit breaker and call metrics.
- **Storefront:** `POST/GET /storefront/v1/checkouts/{id}/payment` (amount from the order; 503 + Retry-After on unknown outcomes).
- **Admin:** `GET …/orders/{id}/payment`, `POST …/orders/{id}/refunds` (Idempotency-Key, `orders:refund`).
- **Webhooks:** `/webhooks/v1/payments/{provider}`.
- **Intent-first provider calls:** `payment_operations` records IN_PROGRESS, SUCCEEDED, FAILED or UNKNOWN with attempts, and every retry reuses the same key.
- **Capture:** payment, order PAID and stock sold commit in one transaction. **Compensation refunds** for captures the order can't take (expired or cancelled, paid twice).
- **Refunds:** partial and full, concurrency-safe, with a durable retry job; pending refunds are settled by webhook. Orders gained `PARTIALLY_REFUNDED` / `REFUNDED`, cancellation after a full refund, and `OrderRefunded`.
- **Recovery:** the `payment-cancellations` consumer of `OrderCancelled`; reconciliation for lost webhooks and unknown outcomes.
- **Jobs:** delayed jobs (`enqueue(..., runAt)`).
- **Migration and docs:** V9; [ADR-017](decisions/ADR-017-payments.md); [domains/payments.md](domains/payments.md); [api/payments.md](api/payments.md).

The "IN_PROGRESS idempotency model" flagged in P7 was not built into the generic `Idempotency` component. It lives in the resources themselves: a payment (one per order) and a refund (unique per `Idempotency-Key`) are committed *before* the provider call, and they are the idempotency records. That's simpler, and it can't drift from the data it protects.

**Tests: 215 total** (61 unit + 154 integration). New: `StripeFormatTest`, `PaymentTest`, `StripePaymentProviderContractTest` (WireMock), `CardPaymentIT` (7), `PaymentRecoveryIT` (6), `RefundIT` (6). Mutation-checked ([EXP-010](experiments/EXP-010-payment-mutations.md)): it found and closed a test gap, a re-sent webhook under a new event id that would have refunded a legitimate payment.

**Verified manually** ([EXP-011](experiments/EXP-011-lost-webhooks.md)):
- Card payment captured by an asynchronous HTTP webhook 0.46 s after confirming.
- Refund, idempotent replay, an over-refund refused with the available amount, and a decline.
- Broken webhook delivery: the order was paid anyway by reconciliation after 38 s.

**Bug found and fixed:** a single payment unknown to the provider aborted every reconciliation run. Items are now isolated, with a regression test.

**Known limitations:**
- no 3-D Secure, disputes or manual capture;
- one payment per order;
- the fake provider's state is in memory;
- no rate limit on payment start (card-testing defense, P14);
- refunds don't restock;
- the Stripe test-mode run is manual (`stripe listen`);
- the order confirmation email is still sent at placement, not at payment (worth revisiting with storefront UX).

**Study before P11 (historical):**
- P10's webhooks from the *sending* side: signing our payloads (the same HMAC scheme merchants will verify), secret rotation with an overlap window;
- delivery as jobs: per-endpoint ordering vs. parallelism, backoff, DEAD, delivery history and manual replay, pausing an endpoint that keeps failing;
- SSRF when calling merchant-supplied URLs: private/link-local/metadata IP ranges, DNS rebinding (resolve once, connect to the checked IP), redirects, response size and time limits;
- mapping internal events (P9 catalog) to public, versioned topics and payloads;
- [blueprint 11](blueprint/11-webhooks.md), [roadmap P11](blueprint/30-roadmap.md).

## P11: Outbound webhooks (as built)

**What exists**
- **`webhooks` module:** subscriptions (`webhooks:manage`, quota 20 under an advisory lock). Six public topics mapped from internal events (orders create, paid, cancelled and fulfilled; refunds; inventory levels), API version `2026-10`.
- **Secrets:** shown once, stored AES-256-GCM.
- **Fan-out and payloads:** idempotent fan-out, with bodies frozen as text.
- **Signing:** `v1=HMAC(timestamp.body)`, with two signatures during a 24 h rotation overlap.
- **Retries:** our own schedule (1m…24h, 9 attempts, ~70 h), `Retry-After`, 3 attempts for 4xx, DEAD.
- **History and recovery:** delivery history with attempts; replay and bulk replay with the same event id; pause after 20 consecutive failures with an email; 30-day retention.
- **SSRF guard:** checks resolved addresses, enforced as the HTTP client's DNS resolver; no redirects; hard total deadline; per-endpoint connection cap.
- **Inventory** publishes `InventoryLevelChanged` (adjustments, sales).
- **Job worker** ([ADR-019](decisions/ADR-019-concurrent-jobs-and-bulkheads.md)): concurrent execution, per-type bulkheads, claims sized to free slots.
- **Migration and docs:** V10; [ADR-018](decisions/ADR-018-outbound-webhooks.md); [domains/webhooks.md](domains/webhooks.md); [api/webhooks.md](api/webhooks.md), which includes the receiver checklist and a reference Python verifier.

**Tests: 264 total** (96 unit + 168 integration). Two consecutive full runs were green.
- New: `EndpointPolicyTest` (29), `SecretBoxTest`, `RetryPolicyTest`, `WebhookDeliveryIT` (12), the job bulkhead test, and the payments regression test.
- Mutation-checked ([EXP-013](experiments/EXP-013-webhook-mutations.md)): it found two bugs in the deadline test (it checked the wrong row, and modelled the wrong threat).

**Verified manually**
- **[EXP-012](experiments/EXP-012-head-of-line-blocking.md):** two instances and an 8 s receiver. The sequential worker delayed an email by **32.8 s** and delivered **4 webhooks twice** (lease expiry). After the fix: 0.2–0.5 s, 0 duplicates. It also found a payments bug (an unknown payment at cancellation retried forever), now fixed.
- **Merchant view:** a Python receiver following only the published checklist verified all deliveries, including after a secret rotation. It observed `orders/paid` before `orders/create` (no ordering guarantee). Every SSRF encoding was refused through the API.

**Known limitations:**
- no per-tenant fairness in the job queue;
- no endpoint ping; no app-owned subscriptions (P12);
- encryption-key rotation is manual;
- the pause threshold counts attempts, not time;
- no ordering guarantee (by design);
- I also rebuilt the jar under a running instance once during verification (a `NoClassDefFoundError`). The lesson: build, then start.

**Study before P12 (historical):**
- API keys for apps: generating, hashing (like refresh tokens), prefixes for identification, scopes, rotation;
- OAuth 2.0 authorization code with PKCE, and Spring Authorization Server: clients, consent, refresh, installations and uninstall;
- distributed rate limiting with Redis (token bucket, atomic Lua or Bucket4j's Redis backends), fail-open vs. fail-closed;
- API versioning by date header and OpenAPI documentation (springdoc);
- app-owned webhook subscriptions;
- [roadmap P12](blueprint/30-roadmap.md).

## P12: Developer platform + Redis (as built)

Built in two milestones (12a merchant-owned access, 12b third-party apps), each tested, verified and committed.

**What exists**
- **`developer` module, credentials and API:**
  - API keys: `sfk_`, SHA-256, shown once, `apps:manage`, anti-escalation;
  - scopes = permissions; `Actor` (user | API client) in `StoreAccess` and the audit log;
  - developer API `/api/2026-10/stores/{storeId}`: products, the orders and inventory "changed since" feeds, idempotent inventory adjustments, app-owned webhook subscriptions;
  - `@RequiresScope` enforced by an architecture test;
  - OpenAPI at `/developer/openapi`.
- **Redis** ([ADR-020](decisions/ADR-020-redis-fail-open.md)): token buckets per credential (40 / 2 per s) and per store (100 / 5 per s), usage counters → `app_usage_hourly`. The API fails open to per-instance limits behind a circuit breaker; health reports DEGRADED (200).
- **OAuth apps** ([ADR-022](decisions/ADR-022-oauth-apps.md)):
  - Spring Authorization Server with code + PKCE (required);
  - login through identity's `PasswordAuthenticator`;
  - a consent page with store, app and scopes;
  - installation at token issuance, with a permission re-check, opaque `sfat_` tokens and `store_id` in the response;
  - no plaintext tokens stored;
  - reinstall replaces the token; uninstall revokes it and deletes the app's subscriptions.
- **[StockSync](../samples/stocksync/README.md):** a stdlib-Python sample app built only on the public contract.
- **Migrations and docs:** V11, V12; [ADR-021](decisions/ADR-021-developer-api-and-credentials.md); [domains/developer.md](domains/developer.md); [api/developer.md](api/developer.md).

**Tests: 284 total** (96 unit + 188 integration). New: `DeveloperApiIT` (8), `RateLimitIT` (4), `OAuthInstallIT` (8). Mutation-checked ([EXP-016](experiments/EXP-016-developer-platform-mutations.md)).

**Verified manually**
- **[EXP-014](experiments/EXP-014-redis-outage.md):** two instances, Redis stopped and restarted. Shared budget 42/60 → per-instance 60/60 at p50 14 ms → 41/60 after recovery. Found and fixed `/actuator/health` answering 503 during the outage.
- **[EXP-015](experiments/EXP-015-third-party-app-lifecycle.md):** StockSync's full life (install, signed events, idempotent restock audited as APP, burst with 429 backoff, uninstall → 401, no subscriptions left).

**Bugs found:**
- usage never counted rejected requests (an interceptor's `afterCompletion` isn't called when `preHandle` throws);
- a test gap for scope escalation through OAuth;
- refill-sensitive flaky tests. One commit (6401c0f) went in with failing tests and was fixed in the next.

**Known limitations:**
- OAuth browser sessions are in memory (multi-instance needs sticky sessions or Spring Session Redis);
- no `app/uninstalled` webhook, no app review or marketplace, offline tokens without expiry;
- credential lookups aren't cached (on purpose);
- usage statistics are lossy during Redis outages;
- every request costs 1 token (no per-endpoint weights).

**Study before P13** (commerce extensions: discounts, shipping, search, caching, object storage):
- **discount/coupon correctness:** usage limits under concurrency (the "last coupon" race: conditional updates vs. locks, P5's patterns), stacking rules, rounding with money;
- **shipping rates and shipments:** rate tables, partial fulfillment;
- **PostgreSQL full-text search** (`tsvector`, GIN, ranking) and **trigram** similarity (`pg_trgm`), and when they stop being enough;
- **caching:** Caffeine for store resolution, cache-aside with Redis only if measured, invalidation and stampedes;
- **object storage** (S3 / MinIO): presigned uploads, content-type and size validation;
- [roadmap P13](blueprint/30-roadmap.md), [blueprint 13](blueprint/13-caching-search.md).

## P13: Commerce extensions (as built)

Built in five milestones, each tested, verified and committed: 13a discounts, 13b shipping and shipments, 13c search, 13d caching (decided against, by measurement), 13e object storage.

**What exists**
- **Discounts** ([ADR-023](decisions/ADR-023-discounts-shipping-and-order-totals.md), [domains/discounts.md](domains/discounts.md)):
  - percentage / fixed codes with window, minimum, usage limit and once-per-customer;
  - applied to a checkout and evaluated live with a reason; re-validated at completion (409);
  - usage limits by conditional update, released when an order is cancelled.
- **Shipping and shipments** ([domains/shipping.md](domains/shipping.md), [orders](domains/orders.md#shipments-p13)):
  - rates by country and weight band, free shipping after discount, chosen at checkout and re-quoted at completion;
  - shipments with tracking under the order lock: partial fulfilment, delivery, cancellation that reopens the order; `fulfillments/create` webhook;
  - `total = subtotal − discount + shipping + tax`, CHECKed on the order.
- **Search** ([ADR-025](decisions/ADR-025-search-in-postgresql-and-no-cache.md)): `GET /storefront/v1/search` with weighted full text, accents, title typos, filters, sorts and disjunctive facets; a tenant-bound definer function for the match step.
- **Caching:** none added. Measured first: a 5k-product store answers every search shape at p95 ≤ 81 ms with 8 clients ([learning note](learning/caching-measure-first.md)).
- **Product images** ([ADR-024](decisions/ADR-024-object-storage-and-presigned-uploads.md), [domains/storage.md](domains/storage.md)): a `storage` module on the S3 API; presigned uploads signed for type and length; completion verifies size and file signature; tenant-prefixed keys; sweeper and job-based deletion; signed image URLs on the storefront. **SeaweedFS replaces MinIO** (no longer published as an image).
- **Migrations and docs:** V13–V17; ADR-023/024/025; domain docs for discounts, shipping, storage; API docs ([discounts](api/discounts.md), [shipping](api/shipping.md), catalog, checkout, orders); [runbook](operations/runbooks/object-storage-and-search.md); `http/commerce.http`.

**Tests: 321 total** (104 unit + 217 integration). New: `DiscountRulesTest`, `RateRulesTest`, `ImageSignaturesTest`, `DiscountIT`, `ShippingIT`, `ShipmentIT`, `ProductSearchIT`, `ProductImagesIT`, the search function in `RowLevelSecurityIT`, the shipment webhook in `WebhookDeliveryIT`. Mutation-checked: coupon conditional update, shipment lock, search tenant scoping, image signature check, signed length, sweep cutoff.

**Verified manually**
- **[EXP-018](experiments/EXP-018-search-at-scale-under-rls.md):** search at 200k products went from 2–15 s (timeouts) to 44–340 ms for one client after three fixes: RLS blocked the text-search indexes (non-leakproof operators), generic plans, and work for rows nobody shows.
- **[EXP-019](experiments/EXP-019-image-upload-safeguards.md):** upload → verify → storefront download against Compose; the store refuses size/type mismatches; spoofed files are discarded.

**Bugs found:**
- `citext = text` comparisons are case-sensitive (codes typed in lowercase didn't match);
- optional booleans had to be `Boolean` for Jackson 3;
- the coupon race test couldn't detect a broken usage limit, because completions are already serialized per store ([EXP-017](experiments/EXP-017-discount-race-and-pitfalls.md));
- search was unusable at scale under RLS (EXP-018).

**Known limitations:**
- one discount per checkout, no automatic or targeted discounts; tax still 0;
- flat shipping rates only; no returns;
- PostgreSQL search: no synonyms or per-language stemming; browsing a 200k catalog takes ~340 ms;
- no thumbnails, CDN or image reordering; image URLs expire after 1 h; no developer-API access to images;
- the seed data from EXP-018 (300k products, 51 stores) is still in the local Compose database: `docker compose down -v` resets it.

**Study before P14** (security hardening):
- **OWASP API Security Top 10 (2023)**, especially BOLA (object-level authorization: our 404 tenant tests), broken function-level authorization and unrestricted resource consumption (search, uploads, storefront without rate limits);
- **threat modelling** (STRIDE per module, trust boundaries: storefront, admin, developer API, webhooks, storage URLs);
- **TOTP MFA** (RFC 6238, recovery codes, step-up for platform admins);
- **security headers and CSP**, HTML sanitization for merchant-entered text (product descriptions);
- **dependency and image scanning** (OWASP Dependency-Check / Trivy, SBOMs) as CI gates;
- **privacy:** customer data export and erasure vs. orders that must be kept (retention, pseudonymization);
- [roadmap P14](blueprint/30-roadmap.md), [blueprint 18](blueprint/18-security.md).

## P14: Security hardening (as built)

Built in six milestones, each tested, verified and committed:
- **14a** the HTTP edge;
- **14b** merchant input and over-posting;
- **14c** platform admin with TOTP;
- **14d** customer data export and erasure;
- **14e** supply chain;
- **14f** function-level authorization and the documentation.

The review started black-box ("what can a client send?") and then went per trust boundary: [threat model](security/threat-model.md), [OWASP API Top 10 mapping](security/owasp-api-top-10.md).

**What exists**
- **Edge** ([ADR-028](decisions/ADR-028-http-edge-hardening.md)):
  - forwarded headers believed only from `TRUSTED_PROXIES`;
  - 1 MB bodies (413);
  - CSP `default-src 'none'` and friends on JSON, a hash-based CSP on the OAuth pages;
  - CORS per API;
  - storefront abuse limits per client address (IPv6 /64) through a shared, fail-open `DistributedRateLimiter` (Redis infrastructure moved to `shared.redis`).
- **Merchant input:** product descriptions sanitized with an allow list (OWASP Java HTML Sanitizer). Unknown JSON fields were already rejected; now proven endpoint by endpoint.
- **Platform admins** ([ADR-026](decisions/ADR-026-platform-admin-and-mfa.md), [domains/platform.md](domains/platform.md)):
  - granted only by an operator (the app role can't INSERT);
  - password + mandatory TOTP (RFC 6238, replay-proof, 5 wrong codes / 15 min), recovery codes;
  - 15-minute tokens with their own audience, re-checked per request;
  - store suspension/reinstatement, job queue, platform audit log; store actions are also visible in the merchant's audit log.
- **Privacy** ([ADR-027](decisions/ADR-027-customer-data-export-and-erasure.md)): customer data export and erasure through a `PersonalData` contract in every module, in one transaction. Accounting is kept; erasure needs the new `customers:erase` permission and is refused while orders are open.
- **Supply chain** ([ADR-029](decisions/ADR-029-supply-chain-and-secrets.md)):
  - CI gates: gitleaks over the history, Trivy over the image (fixable HIGH/CRITICAL);
  - CycloneDX SBOM (CI artifact, `/actuator/sbom` on the private port);
  - actions pinned by SHA, scanners and base images by exact version; Dependabot;
  - Spring Boot 4.0.8 + documented overrides;
  - hardened container (non-root, read-only, no capabilities);
  - development secrets refused at startup when `ALLOW_DEVELOPMENT_SECRETS=false`.
- **Migrations and docs:** V18, V19; ADR-026…029; [api/platform.md](api/platform.md); [conventions](api/conventions.md) (limits and browser rules); runbooks [secrets](operations/runbooks/secrets.md), [platform-admin](operations/runbooks/platform-admin.md); `http/platform.http`.

**Tests: 367 total** (121 unit + 246 integration). New:
- `HttpEdgeIT`, `StorefrontRateLimitIT` (own context, low limits);
- `PropertyLevelAuthorizationIT`, `FunctionLevelAuthorizationIT` (every endpoint, discovered);
- `PlatformAdminIT`, `TotpTest`, `CustomerPrivacyIT` (searches every column of every table);
- `RichTextTest`, `DevelopmentSecretsGuardTest`, `StorefrontRateLimitFilterTest`.

Mutation-checked: [EXP-021](experiments/EXP-021-platform-and-privacy-mutations.md) and the 14a/14b mutations.

**Verified manually**
- **[EXP-020](experiments/EXP-020-forwarded-headers-and-oversized-bodies.md):**
  - rotating `X-Forwarded-For`: 40/40 login attempts accepted before, limited at 30 after;
  - 20 MB body: parsed in 425 ms before, 413 in 3 ms after.
- **[EXP-022](experiments/EXP-022-supply-chain-scan.md):**
  - 47 HIGH/CRITICAL brought to 0;
  - the hardened container runs as uid 999 on a read-only filesystem;
  - startup is refused with development secrets.

**Bugs found:**
- spoofable client addresses defeated every per-IP limit;
- unbounded JSON bodies;
- stored XSS through product descriptions;
- the Docker image hadn't built since P8 (`lombok.config` not copied);
- 47 vulnerable dependencies (and a stale base image behind a floating tag);
- development secrets only warned;
- the MFA limit counted successes;
- a path-based gitleaks exception that hid a planted token.

**Known limitations:**
- TOTP is phishable in real time (WebAuthn later); no MFA for merchants;
- per-address limits don't stop distributed attacks;
- admin permission checks run after Bean Validation (422 before 403);
- logs, provider records and backups are outside erasure;
- encryption keys can't be rotated without re-sealing;
- descriptions written before P14 stay unsanitized until edited;
- rate limits on storefront search apply per address, not per store.

**Study before P15** (load testing & performance):
- **load testing with k6:** scenarios vs. synthetic floods, open vs. closed models, arrival rates, think time; coordinated omission;
- **reading latency:** percentiles, histograms vs. averages, why p99 under load ≠ p99 alone; SLOs and error budgets, burn-rate alerts;
- **PostgreSQL under load:** `pg_stat_statements`, `EXPLAIN (ANALYZE, BUFFERS)` as the app role (EXP-018), connection pool sizing (HikariCP), lock waits, autovacuum;
- **JVM:** async-profiler (CPU, allocation, wall-clock flame graphs), GC logs, virtual threads and pinning;
- **seed data at realistic scale:** the EXP-018 generator as a starting point;
- [roadmap P15](blueprint/30-roadmap.md), [blueprint 21](blueprint/21-reliability-performance.md).

## P15: Load testing & performance (as built)

Built in steps:
1. tooling and baseline;
2. capacity steps;
3. diagnose and fix whatever broke, measuring each fix;
4. SLOs and alerting;
5. docs.

[Performance overview](performance/README.md).

**What exists**
- **Load environment** ([load/README](../load/README.md)):
  - a separate `storeforge_load` database;
  - 50 tenants created through the API, with ~400k orders of history (bulk SQL, resumable);
  - a fixed-JVM launcher;
  - k6 2.3.0 open-model mix (browse, search, full checkout, admin, developer feed; 40 % on the largest store; one client address per iteration) with the SLOs as thresholds;
  - `load/run.sh` stores the k6 summary, top `pg_stat_statements` and pool/JVM metrics per run;
  - pinned async-profiler.
- **SLOs** ([slos.md](performance/slos.md)):
  - storefront reads p95 < 200 ms, p99 < 500 ms; checkout completion p95 < 500 ms; admin and developer p95 < 300 ms; 99.9 % availability;
  - histogram buckets at the thresholds;
  - burn-rate alert rules with promtool tests in CI ([alerting.md](performance/alerting.md), [runbook](operations/runbooks/slo-burn.md)).
- **Fixes** ([EXP-023](experiments/EXP-023-load-baseline.md), [ADR-030](decisions/ADR-030-bounded-concurrency-and-virtual-threads.md)):
  - store lookup outside the cache's lock (a deadlock of pinned virtual threads);
  - load shedding (64 in flight, then 503 + Retry-After);
  - product-by-handle on a text index under RLS (V20);
  - stored normalized search title (V21);
  - index-friendly citext lookups where there's no RLS (login, store domains), guarded by `CitextComparisonTest`;
  - object storage no longer blocks startup.

**Baseline (final):** SCALE 1 (173 req/s) within every objective (storefront p95 36 ms, checkout p95 28 ms). Capacity on the shared 12-core laptop is about SCALE 1.5–2 (~300 req/s). Beyond it the instance sheds load and stays healthy (SCALE 5: 754 req/s, 0 dropped iterations).

**Measured improvements:**

| Fix | Before | After |
|---|---|---|
| Deadlock at 5× load | permanently hung instance | serving |
| Load shedding (SCALE 5) | 252 req/s, 36k dropped, 1,432 full GCs | 754 req/s, 0 dropped, 0 full GCs |
| Load shedding (SCALE 3) | storefront p95 3,029 ms | 224 ms |
| Handle lookup | 3.67 ms | 0.05 ms |
| Search match | 37.5 ms | 13.7 ms (−29 % connection time) |

A bigger pool (20) measurably didn't help: the database CPU is the limit.

**Tests: 370 total** (124 unit + 246 integration). New: `CachingStoreDirectoryTest`, `ConcurrencyLimitFilterTest`, `CitextComparisonTest`; promtool rule tests (3 cases) in CI.

**Bugs found:**
- a deadlock under load (virtual threads pinned in a cache loader);
- unbounded concurrency filling the heap;
- citext comparisons that could never use an index (under RLS: not even with a cast);
- 9 M redundant `unaccent` calls per 2 minutes;
- the app wouldn't start without object storage.

In the tooling:
- temp tables without ANALYZE (seeding took minutes);
- a VU-level client address tripping our own rate limits;
- `docker compose exec` swallowing a loop's stdin.

**Known limitations:**
- Numbers come from one shared machine (app, database and k6 together). Production capacity needs P16's environment.
- Search remains the main database cost (common words rank thousands of matches; facets over all).
- The load-shedding limit is static per instance.
- JavaMail still pins carriers in the email job: JDK 25 recommended (JEP 491).
- The SLO alert rules are tested but not yet loaded by a Prometheus with routing (P16). No SLO dashboard yet.

**Study before P16** (production infrastructure & CD):
- **container deployment:** a VM with Docker Compose vs. managed containers; health checks, rolling restarts, graceful shutdown with in-flight requests;
- **reverse proxy + TLS:** Caddy/Traefik/nginx, Let's Encrypt, forwarding headers and `TRUSTED_PROXIES` (ADR-028);
- **separate API and worker roles** (jobs, sweepers, relays) and what that means for scheduling and the pool;
- **secrets management:** platform secret stores, rotation; the `ALLOW_DEVELOPMENT_SECRETS=false` checklist ([runbook](operations/runbooks/secrets.md));
- **database operations:** migrations as a release step, backups (PITR) and a restore drill;
- **alert routing** (Alertmanager), the SLO rules from P15; staging vs. production; smoke tests and rollback;
- [roadmap P16](blueprint/30-roadmap.md), [blueprint 19](blueprint/19-infrastructure-cicd.md).

## P16: Production infrastructure & CD (as built)

Built and verified on one machine with **staging and production side by side** ([deploy/](../deploy/README.md), [ADR-031](decisions/ADR-031-single-host-production-and-delivery.md)). No cloud account was used: a real VM needs only the host, DNS and a registry login; the scripts and the CD workflow are the same.

**What exists**
- **Roles from one image:** `api` (HTTP), `worker` (jobs, relays, sweepers), `migrate` (release job: Flyway as the owner, then exit). API and worker hold no owner credentials and refuse a schema older than their code (`SchemaVersionGuard`, V22).
- **Stack per environment:**
  - Caddy (TLS, wildcard store hosts, a storage host for presigned URLs, health-checked load balancing with retries) in front of api-1 and api-2, plus a worker;
  - PostgreSQL 17.10 + wal-g, Redis, SeaweedFS (separate app and backup identities), Mailpit as mail relay;
  - Prometheus (SLO and operational rules), Alertmanager (page vs ticket), Grafana (SLO dashboard).
- **Secrets as files** (configtree, named after the variables they replace), generated per environment, development secrets refused, only the proxy trusted.
- **Delivery:**
  - `deploy.sh`: migrate (abort before rollout) → api-1 → api-2 → worker with health gates → end-to-end smoke test through the proxy → automatic rollback; `--rollback`;
  - CD (`deliver.yml`): image built once, pushed by digest with provenance and SBOM, staging, then production after approval; actionlint clean.
- **Data:** continuous WAL archiving (RPO ≤ ~1 min), `backup.sh` (base backups), `restore-drill.sh` (restore next to live, compare, time it).
- **Alerting:** P15 burn-rate rules plus instance down, no worker, dead jobs, sustained shedding; emails to on-call vs tickets (Mailpit locally).

**Verified** ([EXP-024](experiments/EXP-024-production-like-delivery.md)):

| Check | Result |
|---|---|
| Deploy under traffic | 0 errors / 601 requests |
| Broken release (invalid setting) | stopped at migrate, 0 errors |
| Broken release (never healthy) | rolled back automatically, 0 errors / 1,200 |
| Restore drill | identical counts in 10 s, including data only in WAL |
| Instance down | paged by email after 2 min 17 s; resolved notice after recovery |
| Production | from nothing in 1 min 44 s |

**Tests: 371 total** (125 unit + 246 integration). New: `SchemaVersionGuardTest`. The in-memory `RateLimiter` and its test were removed.

**Bugs found:**
- login rate limits were per instance (two API instances = twice the attempts); now shared through Redis, email keys hashed;
- the migrate profile couldn't start without a web server;
- tooling: Compose YAML, IP ranges, a load test tripping our own limits.

**Known limitations:**
- One host = one failure domain (no HA; proxy not replicated).
- No base-backup age metric or scheduler shipped (host cron/timer, documented).
- The CD workflow and GHCR push are untested on GitHub (linted, and the same scripts verified locally).
- Alert routing goes to Mailpit, not a pager.
- Resolved notices wait up to Alertmanager's 5-minute group interval.
- The OAuth login pages' sessions are still per instance (P12 open item): with two API instances, app installs need sticky sessions. Not addressed.

**Study before P17** (failure experiment suite):
- **fault injection with Toxiproxy:** latency, bandwidth, timeouts, resets between app and PostgreSQL, Redis and object storage;
- the [failure experiment catalogue](blueprint/25-failure-experiments.md) and how to write hypotheses and abort conditions; blast radius;
- **steady-state definition:** the SLOs and the k6 mix from P15 as the "normal" to compare against;
- **partial failures:** slow (not down) dependencies, disk full, clock skew, a node killed mid-transaction, a worker killed mid-job;
- what the alerts and runbooks from P15/P16 should say during each experiment (are they useful at 3 a.m.?);
- [roadmap P17](blueprint/30-roadmap.md).
