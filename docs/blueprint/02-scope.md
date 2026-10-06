# 02 — Scope and Feature Map

Rule: a feature earns its place by the **engineering concept it teaches**, not because Shopify has it.

Legend: **Teaches** = concept learned, **Deps** = depends on, **Edge** = edge cases you must handle.

## MVP: the smallest system that is still architecturally meaningful

| Feature | What / why | Teaches | Deps | Edge cases |
|---|---|---|---|---|
| Merchant registration & login | Merchants need accounts | Password hashing, token issuance, refresh rotation, rate limiting of credential endpoints | — | Duplicate email (case-insensitive), unverified email, brute force, enumeration via error messages/timing |
| Email verification & password reset | Prove ownership of email | Single-use, expiring, hashed tokens; async email | Registration, notifications (minimal) | Token reuse, expired token, reset while logged in elsewhere (revoke sessions) |
| Store creation | Merchant creates a tenant | Tenant provisioning in one transaction, unique handles | Identity | Handle collision, reserved handles (`admin`, `api`, `www`) |
| Staff invitations, roles & permissions | Multiple people per store | RBAC, tenant-scoped authorization, privilege escalation prevention | Store | Last owner cannot leave; staff cannot grant permissions they do not hold; invite to existing user vs. new user |
| Product catalog (product, options, variants) | Merchants sell things | Aggregates, validation, pagination/filter/sort, optimistic locking | Store | Duplicate SKU within a store, variant option combination uniqueness, concurrent edits |
| Inventory per variant | Stock tracking | Atomic conditional updates, ledger (append-only transactions), DB check constraints | Catalog | Oversell under concurrency, negative adjustments, untracked inventory (`track_inventory=false`) |
| Storefront browsing | Public read API per store | Tenant resolution from host/handle, public vs. authenticated APIs, caching later | Catalog | Store suspended, product draft/archived, variant out of stock |
| Cart | Customer collects items | Ephemeral state, anonymous identity (cart token), price snapshot vs. live price | Catalog | Variant deleted while in cart, quantity > stock, cart from another store |
| Checkout → Order | Converting a cart into an order | Transaction boundaries, idempotency keys, inventory reservation, state machines | Cart, Inventory | Double click "Pay", stale prices, partial stock, abandoned checkouts (reservation expiry) |
| Payments (fake provider + Stripe test) | Getting paid | Provider abstraction, payment state machine, signed webhooks, duplicate callbacks | Orders, events | Provider timeout (unknown outcome!), duplicated webhook, out-of-order webhook, authorized but order cancelled |
| Order management (merchant) | Fulfil/cancel/refund | State transitions, audit, compensation (release stock, refund) | Orders, Payments | Cancel after shipment, refund more than paid, concurrent cancel and fulfil |
| Transactional outbox + DB job queue | Reliable async work | Outbox, at-least-once, idempotent consumers, `SKIP LOCKED`, backoff, DLQ | Orders | Worker crash mid-job, poison messages, duplicate delivery |
| Email notifications | Order confirmation etc. | Async side effects, retries, templating (minimal) | Outbox | Provider down, duplicate sends |
| Audit log | Who did what | Append-only records, actor modelling | Identity, Store | Logging sensitive data into audit |
| Observability baseline | Logs, metrics, traces | Structured logs, correlation IDs, RED metrics, OTel | Foundation | Cardinality explosion (tenant id as metric label) |

## Phase 2: advanced backend and distributed-system concepts

| Feature | Teaches | Deps | Edge cases |
|---|---|---|---|
| Outbound webhooks (subscriptions, signing, retries, replay, secret rotation) | Reliable delivery to untrusted endpoints, HMAC, SSRF protection, backoff | Outbox | Slow or hanging endpoints, endpoint redirects to internal IP, rotation overlap |
| Developer platform: API keys (private apps) | Machine credentials, hashing, scopes | RBAC | Key leaked → revocation, key prefix lookup |
| OAuth apps (authorization code + PKCE), app installation, scopes | OAuth2 roles, consent, delegated access | API keys, webhooks | Scope upgrade on reinstall, uninstall revokes tokens and webhooks |
| Per-tenant & per-app rate limiting (Redis) | Token bucket, distributed counters, fairness | Dev platform | Redis down: fail-open vs. fail-closed decision |
| Discounts & coupons | Rule evaluation, usage limits under concurrency | Checkout | Coupon usage limit race, stacking, expired during checkout |
| Shipping rates (flat/weight) & shipments | Value objects (weight, money), state machine | Orders | Partial fulfilment |
| Refunds (partial/full) | Compensation, money arithmetic, idempotency | Payments | Refund > captured, concurrent refunds |
| Product search (PostgreSQL FTS + trigram) | Indexing, ranking, GIN indexes | Catalog | Accents, typos, empty queries |
| Caching (Caffeine + Redis) | Cache-aside, invalidation, stampedes | Catalog, storefront | Stale prices, cross-tenant cache key collision |
| Object storage for product images (MinIO / S3) | Presigned uploads, tenant-prefixed keys | Catalog | Upload abandoned, content-type spoofing, huge files |

## Phase 3: production, infrastructure and scaling

| Feature | Teaches |
|---|---|
| Full OTel stack: Collector → Prometheus/Tempo/Loki → Grafana dashboards and alerts | Telemetry pipelines, SLO dashboards, alert design |
| SLOs and error budgets for checkout and the storefront | Reliability as a measured contract |
| k6 load test suite + performance tuning | Methodology: baseline → hypothesis → change → measure |
| Production deployment (VM + Docker Compose or a managed container platform), TLS, reverse proxy | Real infra: DNS, TLS, secrets, health checks, graceful shutdown |
| CD pipeline with migrations and rollback | Expand/contract migrations, image versioning, smoke tests |
| Failure experiment suite (chaos) | Resilience patterns proved, not assumed |
| Noisy-neighbour protection (per-tenant quotas, bulkheads) | Multi-tenant fairness |
| Read replica for storefront reads | Replication lag, read-your-writes |

## Advanced / optional (never blocks the main path)

| Feature | Teaches | Why optional |
|---|---|---|
| Message broker (RabbitMQ) replacing DB queue for webhooks/notifications | Broker semantics, acks, DLX | Only once DB queue limits are *measured* |
| Kafka for an analytics event stream / CDC (Debezium) | Logs vs. queues, partitions, consumer groups | Large operational cost; justified only for stream use cases |
| OpenSearch for product search | Index sync, eventual consistency | PG FTS is enough for learning-scale data |
| Kubernetes deployment | Orchestration, HPA, probes, rolling updates | Compose or a managed platform teaches the essentials first |
| Service extraction (e.g. webhook dispatcher as a separate service) | Distributed boundaries, contracts | Only after module boundaries have proven stable |
| Tenant sharding (store → shard routing) | Horizontal data partitioning | Learning exercise on top of a working system |
| MFA (TOTP + WebAuthn) for merchants/admins | Strong authentication | Valuable but orthogonal |
| Platform billing for merchants (subscription plans) | Metering, entitlement checks | Business-y; moderate learning value |
| Analytics (daily sales rollups, read models) | CQRS-lite, materialized views | After orders are stable |
| Multi-currency, tax engines, multi-location inventory | Domain complexity | Domain-heavy, low platform learning |
| Organization above stores (one merchant, many stores) | Hierarchical tenancy | See ADR-002: store is the tenant |

## Deliberately excluded

Theme editor, page builder, blog, gift cards, POS, marketplace/app store UI polish, product reviews, and abandoned-cart marketing. They add surface area without new engineering concepts.
