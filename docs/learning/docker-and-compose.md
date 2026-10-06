# Docker and Docker Compose

**Problem.** "Works on my machine": different PostgreSQL versions, missing services, manual setup steps, and an app that behaves differently in production because it was built differently.

**Why we need it.** Reproducible dependencies for development and tests, and one immutable artifact (the image) promoted through environments.

**Alternatives.** Local installs (drift), Podman (compatible alternative), devcontainers/Nix (whole toolchain), buildpacks (`spring-boot:build-image`: no Dockerfile, less to learn about layers).

**Decision.** Hand-written multi-stage Dockerfile (to learn what goes into an image) + Compose for local services.

**Mental model.**
- An **image** is a read-only stack of layers, each the result of one instruction. Layers are cached and shared, so ordering matters: things that rarely change (dependencies) go first, code last.
- A **container** is a process running from an image, with its own filesystem view and network namespace. A **volume** keeps data outside the container lifecycle.
- **Multi-stage build:** the JDK and Maven exist only in the build stage; the runtime image contains just the JRE and the extracted application layers.
- PID 1 receives signals. With exec-form `ENTRYPOINT ["java", ...]`, the JVM is PID 1 and gets SIGTERM, which is what makes graceful shutdown work. Shell form would wrap it in `/bin/sh`, which doesn't forward signals.
- Compose: services on one network, reachable by service name (`postgres:5432`); `depends_on: condition: service_healthy` waits for the healthcheck; **profiles** keep optional services off by default.
- `docker-entrypoint-initdb.d` scripts run **only on an empty data directory**.

**Project usage.** `Dockerfile` (build → `jarmode=tools extract --layers` → runtime as user `storeforge`, `MaxRAMPercentage=75`), `compose.yaml` (postgres + `app` profile), `.dockerignore`.

**Failure cases.** Container OOM-killed (heap + metaspace + threads > limit: watch for exit code 137); stale volume after changing init scripts; a port conflict on the host.

**Testing.** CI builds the image on every push. Locally verified: app container healthy, DB outage behaviour ([EXP-001](../experiments/EXP-001-database-unavailable-p1.md)).

**Observability.** `docker compose ps` (health status), `docker compose logs`, `docker stats`.

**When NOT to use it.** For the inner dev loop, running the app on the host (`./mvnw spring-boot:run`) is faster. Compose is for dependencies.
