# StoreForge

A multi-tenant commerce platform (Shopify-inspired), built as a long-term learning project in backend engineering, SaaS architecture, reliability, security, observability and production operations.

> Status: **Phase 16 (Production infrastructure & CD: staging/production stacks, zero-downtime deploys with rollback, backups, alerting) complete.** Next: P17 (failure experiment suite). See [docs/PROGRESS.md](docs/PROGRESS.md).

## Quick start

Prerequisites: Java 21, Docker (with Compose v2). Maven is provided by the wrapper.

```bash
docker compose up -d                 # PostgreSQL 17 (owner/app roles), Mailpit (emails at http://localhost:8025), Redis, SeaweedFS (S3 API at :8333)
./mvnw spring-boot:run               # API on :8080, management (health/metrics) on :8081

curl localhost:8081/actuator/health/readiness
curl -i localhost:8080/anything      # → 404 application/problem+json with a requestId
# Walkthroughs: http/auth.http (register → verify → login → refresh), http/stores.http (store, roles, invitations, audit), http/catalog.http (products, If-Match, storefront), http/inventory.http (stock, ledger), http/storefront.http (cart → checkout → idempotent completion), http/orders.http (pay, fulfil, cancel)
```

Run the app in a container instead: `docker compose --profile app up -d --build`.

Observability: `docker compose --profile observability up -d` and run the app with `SPRING_PROFILES_ACTIVE=otel`, then open Grafana at http://localhost:3000 (dashboard *StoreForge Overview*).

## Tests

```bash
./mvnw test      # unit tests + module boundary verification (no Docker needed)
./mvnw verify    # + integration tests against real PostgreSQL (Testcontainers; Docker required)
```

## Documentation

Start at [docs/README.md](docs/README.md).
- **Blueprint** (architecture and roadmap, the source of truth): [docs/blueprint/](docs/blueprint/README.md)
- **Decisions:** [docs/decisions/](docs/decisions/README.md)
- **Running locally:** [docs/infrastructure/local-development.md](docs/infrastructure/local-development.md)
