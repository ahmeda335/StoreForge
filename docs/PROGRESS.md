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
| P10 Payments | ⏭ Next | Study list below |
| P11–P18 | Planned | [Roadmap](blueprint/30-roadmap.md) |

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

**Study before P10:** card payment flow (authorization vs. capture, 3-D Secure / client-side confirmation, why the server never sees card numbers: PCI DSS SAQ-A); Stripe PaymentIntents and their statuses; the "unknown outcome" of a timed-out provider call and idempotency keys toward providers; verifying webhook signatures (HMAC over the raw body, timestamp tolerance, replay); an IN_PROGRESS idempotency model for external calls (ADR-013, EXP-007); Resilience4j timeouts/circuit breakers; WireMock for contract tests; [blueprint 10](blueprint/10-payments.md).
