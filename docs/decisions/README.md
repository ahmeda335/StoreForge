# Architecture Decision Records

An ADR captures a decision that someone (including future you) would ask "why?" about in six months.
Template: [TEMPLATE.md](TEMPLATE.md). Status values: Proposed · Accepted · Superseded by ADR-xxx.

| ADR | Title | Status | Phase |
|---|---|---|---|
| [001](ADR-001-modular-monolith.md) | Modular monolith with enforced module boundaries | Accepted | P0 |
| [002](ADR-002-multi-tenancy-strategy.md) | Shared schema, store = tenant, PostgreSQL RLS | Accepted, refined by 009 | P0 (implemented P3) |
| [003](ADR-003-authentication-strategy.md) | JWT access tokens + rotating opaque refresh tokens | Accepted, refined by 008 | P0 (implemented P2) |
| [004](ADR-004-inventory-concurrency.md) | Atomic conditional updates + CHECK constraints for stock | Accepted, implemented & measured (EXP-004) | P0 (implemented P5) |
| [005](ADR-005-async-processing.md) | Transactional outbox + PostgreSQL job queue before any broker | Accepted, implemented (ADR-016) | P0 (implemented P9) |
| [006](ADR-006-foundation-stack.md) | Foundation stack: Java 21, Spring Boot 4, Maven, Flyway, separate DB roles | Accepted | P1 |
| [007](ADR-007-error-and-correlation-model.md) | RFC 9457 problem details + request IDs | Accepted | P1 |
| [008](ADR-008-token-details-and-revocation.md) | Access-token format (RFC 9068), security-stamp revocation, strict refresh rotation | Accepted | P2 |
| [009](ADR-009-tenant-context-and-directory-tables.md) | Tenant directory vs. tenant data; explicit tenant transactions; 404 for non-members | Accepted | P3 |
| [010](ADR-010-catalog-persistence-and-concurrency.md) | JPA aggregates for writes, SQL read models for lists, ETag/If-Match optimistic concurrency | Accepted | P4 |
| [011](ADR-011-cross-tenant-background-work.md) | Cross-tenant background work via a store-id-only SECURITY DEFINER function | Accepted | P5 |
| [012](ADR-012-observability-pipeline.md) | Micrometer Observation → OpenTelemetry, OTLP push, grafana/otel-lgtm locally | Accepted | P6 |
| [013](ADR-013-checkout-and-idempotency.md) | Checkout scope (guest customers, order in P8), expectedTotal guard, transactional idempotency | Accepted | P7 |
| [014](ADR-014-orders-lifecycle.md) | Order state machine, gapless per-store numbers, the order owns the payment deadline, locked transitions | Accepted | P8 |
| [025](ADR-025-search-in-postgresql-and-no-cache.md) | Product search in PostgreSQL (weighted full text + trigram), matched by a tenant-bound definer function because RLS blocks non-leakproof index conditions; no cache (measured) | Accepted | P13 |
| [024](ADR-024-object-storage-and-presigned-uploads.md) | Object storage on the S3 API: presigned uploads signed for type and length, stored object verified on completion, tenant-prefixed keys, SeaweedFS instead of MinIO | Accepted | P13 |
| [023](ADR-023-discounts-shipping-and-order-totals.md) | Discounts and shipping priced in checkout (fixed pipeline), conditional-update usage limits, 409 on stale choices, totals frozen on the order; shipments for partial fulfilment | Accepted | P13 |
| [022](ADR-022-oauth-apps.md) | Third-party apps on Spring Authorization Server: PKCE, consent with store choice, permission re-check at issuance, installation-bound opaque tokens, no stored plaintext tokens | Accepted | P12 |
| [021](ADR-021-developer-api-and-credentials.md) | Developer API: opaque hashed credentials (instant revocation), scopes = permissions, `Actor`, `@RequiresScope` + architecture test, date-versioned paths | Accepted | P12 |
| [020](ADR-020-redis-fail-open.md) | Redis for shared, losable state (rate limits, usage); fail open to per-instance limits; DEGRADED health | Accepted | P12 |
| [019](ADR-019-concurrent-jobs-and-bulkheads.md) | Job worker runs jobs concurrently with per-type bulkheads; claims only what can start (fixes head-of-line blocking and lease-expiry duplicates) | Accepted | P11 |
| [018](ADR-018-outbound-webhooks.md) | Outbound webhooks: own retry schedule, SSRF check as the HTTP client's DNS resolver, AES-GCM secrets, frozen payloads, pause + replay | Accepted | P11 |
| [017](ADR-017-payments.md) | Payments: provider calls outside transactions, order updated in the same transaction, automatic compensation refunds, signed webhooks + reconciliation, no SDK + stateful fake | Accepted | P10 |
| [016](ADR-016-async-infrastructure.md) | Outbox/jobs/emails as platform infrastructure (no RLS), durable email, thin events, at-least-once | Accepted | P9 |
| [015](ADR-015-lombok.md) | Lombok allow-list: constructors, getters, loggers, JPA no-arg constructors; no @Data/@Setter/@ToString | Accepted | P8→P9 |
