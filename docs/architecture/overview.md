# Architecture Overview (as built)

> This page describes what **exists in code now**. The target architecture is in [blueprint/03-architecture.md](../blueprint/03-architecture.md). Diagrams marked *planned* link to the blueprint and are redrawn here when their phase is implemented.

Current phase: **P1 Foundation**.

## System context (P1)

```mermaid
flowchart LR
    Client[HTTP client] -->|":8080 public API"| App
    Ops[Operator / orchestrator / Prometheus] -->|":8081 management: health, metrics"| App
    subgraph App[StoreForge application, one process]
        F[RequestIdFilter] --> MVC[Spring MVC] --> EH[ApiExceptionHandler]
    end
    App -->|"JDBC as storeforge_app (DML)"| PG[(PostgreSQL 17<br/>schema storeforge)]
    FW[Flyway at startup] -->|"as storeforge_owner (DDL)"| PG
    App -->|"JSON logs on stdout"| Logs[(container log)]
```

## Request flow (P1)

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

Defined in [blueprint/03-architecture.md](../blueprint/03-architecture.md#layers-inside-a-module): `web → application → domain ← persistence`, with ports only at external boundaries. In P1 only the `shared` module exists:

| Module | Type | Contents | Allowed dependents |
|---|---|---|---|
| `shared` | OPEN (all sub-packages usable) | `shared.web`: `RequestIds`, `RequestIdFilter`, `ApiExceptionHandler` | All modules |

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
| Authentication / token refresh | P2 | [07](../blueprint/07-authentication-authorization.md) |
| Tenant resolution and RLS | P3 | [04](../blueprint/04-multi-tenancy.md#request-context) |
| Inventory reservation | P5 | [06](../blueprint/06-database.md#inventory-preventing-oversell) |
| Checkout → order | P7–P8 | [06](../blueprint/06-database.md#transactions-and-isolation) |
| Outbox → jobs → handlers | P9 | [09](../blueprint/09-events-async.md#transactional-outbox) |
| Payment | P10 | [10](../blueprint/10-payments.md#lifecycle-and-state-machine) |
| Webhook delivery | P11 | [11](../blueprint/11-webhooks.md) |
| OAuth app installation | P12 | [12](../blueprint/12-developer-platform.md#installation-flow-oauth-20-authorization-code--pkce) |
