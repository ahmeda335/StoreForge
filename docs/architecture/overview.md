# Architecture Overview (as built)

> This page describes what **exists in code now**. The target architecture is in [blueprint/03-architecture.md](../blueprint/03-architecture.md). Diagrams marked *planned* link to the blueprint and are redrawn here when their phase is implemented.

Current phase: **P4 Catalog** (complete).

## System context (P4)

```mermaid
flowchart LR
    Client[HTTP client / SPA] -->|":8080 /auth/v1, /admin/v1"| App
    Shopper[Shopper browser] -->|":8080 /storefront/v1 (Host = store)"| App
    Ops[Operator / orchestrator / Prometheus] -->|":8081 management: health, metrics"| App
    subgraph App[StoreForge application, one process]
        F[RequestIdFilter] --> SEC[Spring Security chains<br/>auth · admin · fallback] --> MVC[Spring MVC controllers] --> EH[ApiExceptionHandler]
        MVC --> ID[identity module]
        MVC --> ST[stores module]
        MVC --> CA[catalog module]
        CA --> ST
        CA --> AU
        ST --> ID
        ST --> AU[audit module]
        ST --> NT
        ID --> NT[notifications module]
    end
    App -->|"JDBC as storeforge_app (DML)"| PG[(PostgreSQL 17<br/>schema storeforge)]
    NT -->|SMTP| MAIL[Mailpit / email provider]
    FW[Flyway at startup] -->|"as storeforge_owner (DDL)"| PG
    App -->|"JSON logs on stdout"| Logs[(container log)]
```

## Request flow (unauthenticated request, P1)

```mermaid
sequenceDiagram
    participant C as Client
    participant RF as RequestIdFilter (first filter)
    participant DS as DispatcherServlet
    participant EH as ApiExceptionHandler
    C->>RF: GET /x  (X-Request-Id: optional)
    RF->>RF: accept if [A-Za-z0-9-]{8,64}, else generate<br/>MDC.put(requestId), response header
    RF->>DS: continue chain
    DS-->>EH: NoResourceFoundException (no controller yet)
    EH-->>C: 404 application/problem+json {code, requestId, ...}
    RF->>RF: finally: MDC.remove(requestId)
```

## Layers inside a module

Defined in [blueprint/03-architecture.md](../blueprint/03-architecture.md#layers-inside-a-module): `web → application → domain ← persistence`, with ports only at external boundaries. Modules so far:

| Module | Type | Contents | Allowed dependents |
|---|---|---|---|
| `shared` | OPEN (all sub-packages usable) | `web` (request IDs, problem details), `error` (`StoreForgeException`, `ConstraintViolations`), `ids` (UUIDv7), `ratelimit`, `time` (`Clock`) | All modules |
| `notifications` | Closed | API: `EmailSender`, `EmailMessage`; internal SMTP adapter | identity (later: outbox jobs) |
| `identity` | Closed | API: `UserPrincipal`, `UserDirectory`; internal web/application/domain/persistence/security | Any module's controllers (via `@AuthenticationPrincipal`); stores |
| `audit` | Closed | API: `AuditLog`, `AuditEntry`; internal JDBC implementation | stores (and every future tenant module) |
| `stores` | Closed | API: `StoreAccess`, `StoreMember`, `Permission`, `StoreDirectory`, `StoreInfo`, `@CurrentStorefront`, exceptions | Every tenant-scoped module |
| `catalog` | Closed | No public API yet; internal domain (Product aggregate), persistence (JPA + SQL read models), admin + storefront controllers | — |

Shared kernel additions in P3: `tenancy` (`StoreId`, `TenantTransactions`), `pagination` (`Cursor`, `Page`), `crypto` (`SecretTokens`), `email` (`EmailAddress`).

### Tenant isolation layers (P3)

```mermaid
flowchart TB
    R["Request /admin/v1/stores/{storeId}/..."] --> A1["1. Authentication: JWT → UserPrincipal"]
    A1 --> A2["2. TenantTransactions.inStore(storeId): BEGIN + set_config(app.current_store_id, LOCAL)"]
    A2 --> A3["3. StoreAccess.require: membership + permission (404 / 403)"]
    A3 --> A4["4. Repositories: explicit store_id predicates"]
    A4 --> A5["5. PostgreSQL RLS: USING / WITH CHECK on tenant-data tables"]
    A5 --> A6["6. Composite FKs (store_id, id): no cross-store references"]
```

See [domains/stores.md](../domains/stores.md#request-flow-tenant-scoped-endpoint) for the full sequence.

### Security filter chains (P2)

| Order | Matcher | Rules |
|---|---|---|
| 1 | `/auth/**` | permitAll; stateless; endpoints protect themselves (rate limits, CSRF checks on cookie endpoints) |
| 2 | `/admin/**` | Bearer JWT required (`NimbusJwtDecoder` ES256 + RFC 9068 validators → `UserTokenConverter`); problem-details 401/403 |
| 3 | `/storefront/**` | Public (anonymous); store resolved from the hostname by `@CurrentStorefront` |
| last | everything else | permitAll (unknown paths → 404; actuator lives on the private management port) |

Authentication and authorization flows: see [domains/identity.md](../domains/identity.md#flows).

Boundaries are verified by `ModularityTest` (Spring Modulith). See [ADR-001](../decisions/ADR-001-modular-monolith.md).

## Deployment view (P1)

```mermaid
flowchart TB
    subgraph compose[Docker Compose project 'storeforge']
        PG[(postgres:17<br/>volume postgres-data<br/>init: infra/postgres/init)]
        APP["app (profile 'app')<br/>image built from Dockerfile<br/>non-root, JRE 21"]
    end
    APP --> PG
    Dev[./mvnw spring-boot:run on host] --> PG
```

## Planned flows (diagrams in the blueprint until implemented)

| Flow | Phase | Blueprint diagram |
|---|---|---|
| Authentication / token refresh | P2 ✅ | [domains/identity.md](../domains/identity.md#flows) |
| Tenant context and RLS | P3 ✅ | [domains/stores.md](../domains/stores.md#request-flow-tenant-scoped-endpoint) |
| Storefront host → store resolution | P4 ✅ | [domains/catalog.md](../domains/catalog.md#flows) |
| Product edit with optimistic concurrency | P4 ✅ | [domains/catalog.md](../domains/catalog.md#flows) |
| Inventory reservation | P5 | [06](../blueprint/06-database.md#inventory-preventing-oversell) |
| Checkout → order | P7–P8 | [06](../blueprint/06-database.md#transactions-and-isolation) |
| Outbox → jobs → handlers | P9 | [09](../blueprint/09-events-async.md#transactional-outbox) |
| Payment | P10 | [10](../blueprint/10-payments.md#lifecycle-and-state-machine) |
| Webhook delivery | P11 | [11](../blueprint/11-webhooks.md) |
| OAuth app installation | P12 | [12](../blueprint/12-developer-platform.md#installation-flow-oauth-20-authorization-code--pkce) |
