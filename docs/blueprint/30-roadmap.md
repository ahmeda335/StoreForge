# 30 — Implementation Roadmap (dependency order)

Every phase ends runnable, tested, observable and documented. "Not yet" lists are as important as the task lists: they stop scope creep.

Progress is tracked in [`docs/PROGRESS.md`](../PROGRESS.md).

```text
P0 Blueprint ─▶ P1 Foundation ─▶ P2 Identity ─▶ P3 Stores/Tenancy/AuthZ ─▶ P4 Catalog ─▶ P5 Inventory
   ─▶ P6 Observability stack ─▶ P7 Cart & Checkout ─▶ P8 Orders ─▶ P9 Outbox/Jobs/Notifications
   ─▶ P10 Payments ─▶ P11 Webhooks ─▶ P12 Developer platform (+Redis) ─▶ P13 Commerce extensions
   ─▶ P14 Security hardening ─▶ P15 Load & performance ─▶ P16 Production infra & CD
   ─▶ P17 Failure experiment suite ─▶ P18 Scaling & selective extraction
Frontend tracks: F1 merchant admin (after P8), F2 storefront (after P10)
```

Why this order differs from the prompt's sketch: **observability (P6) comes before checkout**, so the most complex flows are built with tracing already in place; **outbox/jobs (P9) come before payments**, because payment processing depends on reliable async; P2's email needs are covered by a minimal synchronous dev mailer that P9 replaces (a documented, temporary limitation).

---

## P0 — Blueprint and prerequisites ✅
- **Objective:** a written architecture you can defend. **Output:** `docs/blueprint/*`, ADR-001…006.
- **Study first:** see [27-learning-map.md](27-learning-map.md) "Must understand before implementation".
- **DoD:** you can explain, without notes, why: modular monolith, shared schema + RLS, conditional update for stock, outbox before broker.

## P1 — Project foundation
- **Objective:** a runnable, testable, observable empty platform with enforced module boundaries.
- **Features:** Spring Boot app, PostgreSQL via Compose, Flyway, two DB roles (owner/app), health (liveness/readiness) on a management port, Prometheus metrics endpoint, structured JSON logs, request IDs, RFC 9457 error format, module boundary verification, Dockerfile, CI.
- **Concepts:** build lifecycle, twelve-factor config, migrations, least-privilege DB roles, health semantics, correlation IDs.
- **Tech:** Java 21, Spring Boot 4, Maven, PostgreSQL 17, Flyway, Testcontainers, Spring Modulith, Docker Compose, GitHub Actions.
- **Study first:** Maven lifecycle (surefire vs. failsafe), Spring Boot auto-configuration, Docker images vs. containers vs. volumes, Flyway versioning.
- **Implementation:** skeleton, `shared.web` (RequestIdFilter, ProblemDetails advice), config, compose + init script for roles, V1 migration (default privileges), Dockerfile (multi-stage, non-root), CI workflow.
- **Testing:** unit (request-id validation); integration (context + Flyway on real PG, app role cannot DDL, health endpoints, 404 problem format with requestId); architecture (Modulith verify).
- **Observability:** JSON logs with requestId, `/actuator/prometheus`, health groups.
- **Security:** app runs as a non-owner DB role; actuator on a separate port; no stack traces in errors; `.env` gitignored.
- **Failure scenarios:** DB down at startup (fails fast with a clear message); DB down at runtime (readiness DOWN, liveness UP).
- **DoD:** `./mvnw verify` green locally and in CI; `docker compose up` + app run; docs for local dev, logging, error format, ADRs.
- **Not yet:** any business entity, security/auth, Redis, OTel tracing export, Kubernetes.

## P2 — Identity and authentication
- **Features:** register, verify email, login, refresh rotation with reuse detection, logout, password reset, account lockout, JWKS.
- **Concepts:** password hashing, token types, JWT validation, CSRF vs. bearer, enumeration resistance.
- **Tech:** Spring Security, OAuth2 Resource Server (JWT decoding), Nimbus JOSE, Argon2 (BouncyCastle), Mailpit.
- **Study first:** JWT structure & validation (RFC 7519/8725), Spring Security filter chain, cookies (`HttpOnly`, `SameSite`).
- **Implementation:** `identity` module; tables `users`, `refresh_tokens`, `email_tokens`; security chains per API path; dev mailer (sync, after commit).
- **Testing:** unit (token hashing, rotation logic); API (401 paths, forged tokens, reuse detection revokes family, uniform responses); integration (unique email race → 409).
- **Observability:** `auth_login_attempts_total{outcome}`; no PII/token in logs (log-scan test begins here).
- **Security:** rate limiting on `/auth/*` (in-memory Bucket4j), lockout, secure cookie flags.
- **Failure:** mail server down → registration still succeeds; user can request resend.
- **DoD:** full auth flow via `.http` file; ADR-003 updated with any changes.
- **Not yet:** MFA, OAuth server, customer accounts, Redis.

## P3 — Stores, tenancy and authorization
- **Features:** create store, store domains (dev host resolution), memberships, invitations, roles/permissions, tenant context, **RLS**, audit log (basic).
- **Concepts:** multi-tenancy, RLS, `SET LOCAL`, RBAC, anti-escalation, write skew.
- **Study first:** PostgreSQL RLS & roles, transaction-scoped settings, Spring request scope, RBAC.
- **Testing:** isolation suite foundation (A vs. B), RLS meta-test, last-owner race test, escalation tests.
- **Observability:** `storeId` in MDC/log fields.
- **Security:** 404 for foreign stores; audit for membership/role changes.
- **Failure:** tenant context missing → queries return nothing (verify), never another tenant.
- **Not yet:** custom domain DNS verification, platform admin UI.

## P4 — Catalog
- **Features:** products, options, variants, status lifecycle, admin CRUD with keyset pagination/filter/sort, storefront read endpoints, optimistic locking (ETag/If-Match).
- **Concepts:** aggregates, keyset pagination, optimistic locking, composite FKs, DTO projections, N+1.
- **Testing:** pagination stability under inserts; 409/412 on concurrent edit; query-count assertions; isolation suite extended.
- **Not yet:** images/object storage, search beyond simple `ILIKE`, caching.

## P5 — Inventory and concurrency
- **Features:** inventory items per variant, adjustments, ledger, reservations (reserve/commit/release API used later by checkout), expiry sweeper.
- **Concepts:** lost update, conditional updates, CHECK constraints, deadlocks and lock ordering, `SKIP LOCKED`, isolation levels.
- **Study first:** PG MVCC, row locks, `FOR UPDATE`/`SKIP LOCKED`, isolation levels (read the PG docs chapter 13).
- **Testing:** 100-thread last-item test (repeated); mixed-order multi-item no-deadlock test; ledger reconciliation invariant; experiment: compare naïve vs. conditional vs. SERIALIZABLE and document results.
- **Observability:** `inventory_reservation_failures_total`, lock wait visible in JDBC spans (after P6).
- **Not yet:** multi-location inventory, Redis counters.

## P6 — Observability stack and tracing
- **Features:** OTel via `spring-boot-starter-opentelemetry`, `grafana/otel-lgtm` in Compose (profile), JDBC tracing, custom observations for catalog/inventory, first dashboards (RED per API, DB pool, JVM), exemplars linking metrics→traces, log↔trace correlation.
- **Concepts:** traces/spans/context propagation, sampling, histograms, cardinality.
- **Study first:** OTel concepts, Prometheus data model & PromQL basics.
- **DoD:** you can click from a slow request on a dashboard to its trace to its logs.
- **Not yet:** alerting rules (P15/16), tail sampling.

## P7 — Customers, cart and checkout
- **Features:** customers (store-scoped), carts with cart tokens, checkout session (address, totals snapshot), **idempotency key infrastructure**, checkout completion that reserves stock.
- **Concepts:** idempotency, bearer secrets, server-side price computation, money arithmetic.
- **Testing:** same idempotency key ×20 concurrently → one result; different body with same key → 422; price tampering ignored.
- **Not yet:** discounts, shipping rates (flat zero), payment.

## P8 — Orders
- **Features:** order placement (from checkout, one TX), per-store order numbers, order state machine, cancel (release stock), merchant order list/detail, reservation expiry for unpaid orders.
- **Concepts:** state machines, compensation, transaction boundaries across modules.
- **Testing:** state machine unit tests; cancel/fulfil race; isolation suite.
- **Not yet:** payments (orders stay PENDING_PAYMENT; a dev endpoint marks paid), webhooks.
- **Frontend F1** can start: admin login, products, inventory, orders.

## P9 — Outbox, job queue, notifications
- **Features:** `outbox_events`, relay, `jobs` with leases/backoff/DEAD, inbox (`processed_messages`), trace propagation through async, email notifications (replacing the P2 dev mailer), admin endpoints for dead jobs.
- **Concepts:** dual-write problem, at-least-once, idempotent consumers, backoff+jitter, DLQ, leases.
- **Study first:** [Transactional outbox](27-learning-map.md#transactional-outbox), `SKIP LOCKED`, at-least-once semantics.
- **Testing:** crash between steps (simulate with exceptions at each step) → no loss, duplicates handled; concurrency of N relays; poison message → DEAD.
- **Observability:** queue depth, oldest age, outbox unpublished, job duration; traces from request to worker.
- **Not yet:** RabbitMQ/Kafka.

## P10 — Payments
- **Features:** `PaymentProvider` port, fake provider with failure modes, Stripe test-mode adapter, payment state machine, inbound provider webhooks with signature + inbox, capture, void, refunds (full/partial), reconciliation job.
- **Concepts:** unknown outcomes, idempotency toward providers, webhook verification, compensation.
- **Testing:** contract tests with recorded Stripe payloads (WireMock); duplicate/out-of-order webhook tests; refund race; timeout → UNKNOWN → reconciliation.
- **Security:** no PAN; signature verification; amounts server-side.
- **Frontend F2** can start: storefront purchase flow.
- **Not yet:** multiple currencies, saved cards.

## P11 — Outbound webhooks
- **Features:** subscriptions, topic mapping, signing, delivery jobs, backoff, DEAD, history, replay, secret rotation, SSRF guard, per-endpoint pause.
- **Testing:** WireMock receiver (slow, 500, redirect to internal IP); signature contract test; rotation overlap test.
- **Not yet:** app-owned subscriptions (P12).

## P12 — Developer platform (+ Redis)
- **Features:** API keys, scopes, developer API version `2026-10`, OAuth apps via Spring Authorization Server, installations, uninstall cleanup, **Redis** token-bucket rate limits per store/installation, usage metrics, OpenAPI docs, StockSync sample app.
- **Concepts:** OAuth2 code+PKCE, scopes, distributed rate limiting, API versioning.
- **Failure:** Redis down → documented fail-open/closed per API.

## P13 — Commerce extensions
- **Features:** discounts & coupons (usage-limit race), shipping rates & shipments, PG full-text search + trigram, caching (Caffeine store resolution, Redis product cache if measured), object storage (MinIO) with presigned uploads.
- **Each feature carries its own concept** (see 02-scope Phase 2 table).
- **As built:** no product cache (measured, ADR-025); SeaweedFS instead of MinIO (ADR-024).

## P14 — Security hardening
- **Features:** full audit coverage, platform admin API with MFA (TOTP), CSP and security headers, dependency/image scanning gates, HTML sanitization, privacy endpoints (export/delete customer), threat-model review per module, pen-test style test suite (OWASP API Top 10 checklist).

- **As built (P14):** see PROGRESS; ADR-026…029, [threat model](../security/threat-model.md), [OWASP mapping](../security/owasp-api-top-10.md). Merchant MFA and impersonation deferred.

## P15 — Load testing and performance
- **Features:** seed-data generator, k6 scenario suite, baselines, `pg_stat_statements`, async-profiler runs, SLO dashboards + burn-rate alerts.
- **DoD:** documented baselines and at least three measured improvements (with before/after).

- **As built (P15):** [performance](../performance/README.md), EXP-023, ADR-030. No async-profiler finding was needed (the bottlenecks were the database and concurrency); SLO dashboard deferred to P16 with alert routing.

## P16 — Production infrastructure and CD
- **Features:** VM or managed container deployment, reverse proxy + TLS, separate api/worker roles, secrets management, migration job, staging + prod environments, smoke tests, rollback procedure, backups + restore drill, alerts routed (e.g. email/Telegram/Slack).

- **As built (P16):** [deploy/](../../deploy/README.md), ADR-031, EXP-024: one Docker host per environment, verified locally (staging and production side by side). A real VM, DNS and registry pushes need accounts; the scripts and the workflow are ready for them.

## P17 — Failure experiment suite
- Run and document all experiments from [25-failure-experiments.md](25-failure-experiments.md) against staging with Toxiproxy. Fix what breaks; record it.

## P18 — Scaling and selective extraction (optional/advanced)
- Read replica for storefront reads (replication lag, read-your-writes); RabbitMQ for webhook dispatch if measured; extract the webhook dispatcher as a service; PgBouncer; partition `audit_log`/`jobs`; sharding thought experiment or Citus prototype; Kubernetes deployment; Kafka + Debezium CDC for analytics.
