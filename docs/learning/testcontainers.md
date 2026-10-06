# Testcontainers

**Problem.** Tests against H2 or mocks pass while production fails: different SQL dialect, no RLS, no `SKIP LOCKED`, different locking and constraint behaviour. A shared test database makes tests interfere with each other.

**Why we need it.** Our correctness depends on PostgreSQL features (roles, constraints, later RLS and row locks). Only the real database proves them.

**Alternatives.** H2/HSQLDB (wrong semantics); embedded-postgres binaries (no Docker, less flexible); a shared CI database (state leaks, flaky).

**Decision.** Testcontainers PostgreSQL 17, the same major version as Compose.

**Mental model.** The test JVM uses the Docker API to start a throwaway container, waits until it is ready, and exposes its random host port. A sidecar container (Ryuk) removes it when the JVM exits, even after a crash. The **singleton pattern** starts the container once per JVM (static initializer) and shares it across test classes. Spring's test context cache shares the application context too, so each IT class costs milliseconds after the first.

**Project usage.** `support/PostgresTestDatabase` copies `infra/postgres/init/01-create-roles.sh` into the container's `docker-entrypoint-initdb.d`, so tests get the same roles as Compose. `@DynamicPropertySource` points `spring.datasource.*` at the app role and `spring.flyway.*` at the owner role.

**Configuration.** Testcontainers 2.x (managed by the Spring Boot BOM), artifact `testcontainers-postgresql`, class `org.testcontainers.postgresql.PostgreSQLContainer`.

**Failure cases.** Docker not running (tests error at startup); slow first run (image pull); tests sharing data. Each test must create its own uniquely named data (from P3: random store handles).

**Testing.** It *is* the testing tool. Verify it by breaking something real (e.g. remove a GRANT) and watching `DatabaseRolesIT` fail.

**Observability.** Container logs: `docker logs <container>` while a test is paused in the debugger.

**When NOT to use it.** Pure domain logic (value objects, state machines): plain unit tests are faster and clearer.
