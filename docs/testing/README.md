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

## Current suite (P1)

| Test | Level | Protects |
|---|---|---|
| `RequestIdsTest` | Unit | Header validation incl. log-forging inputs; generated ID format |
| `ModularityTest` | Architecture | Module boundaries, no cycles (Spring Modulith) |
| `DatabaseRolesIT` | Integration | Runtime role is non-owner, cannot DDL (42501), migrations applied by owner, DB-side timeouts present |
| `ErrorResponseIT` | API | Problem Details format, `code`, `requestId`; request-ID propagation/replacement; opaque 500 logged as JSON with the request ID |
| `OperationalEndpointsIT` | API | Liveness excludes DB; readiness includes DB; Prometheus metrics present; actuator not on the public port |
