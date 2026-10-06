# 26 — Technology Decision Matrix

Status: **Core** (used from its phase on), **Phase-gated** (introduced when its problem appears), **Optional** (learning exercise; not required for the system to be complete).

| Technology | Problem it solves | Why selected | Alternatives | Introduce | Study before using | Status |
|---|---|---|---|---|---|---|
| **Java 21** | Typed, fast, mature runtime with excellent tooling and libraries | Records, sealed types, pattern matching, virtual threads; LTS | Kotlin, Go, C# | P1 | Records, `Optional`, streams, `java.time`, concurrency basics | Core |
| **Spring Boot 4** | Wiring, config, HTTP, data, security, observability with conventions | Industry standard; deep docs; first-class Testcontainers & observability | Quarkus, Micronaut, plain Jakarta EE | P1 | DI/IoC, auto-config, profiles, `@Transactional` proxies | Core |
| **Maven** | Reproducible builds, dependency management | Ubiquitous, declarative, Failsafe/Surefire split for IT | Gradle | P1 | Lifecycle, BOMs, plugins | Core |
| **PostgreSQL 17** | Durable, transactional, constraint-enforcing storage | RLS, `SKIP LOCKED`, partial indexes, JSONB, full-text search, `citext`: one DB covers queue, search, tenancy | MySQL (no RLS), CockroachDB (distributed, costly semantics) | P1 | SQL, indexes, MVCC, locking, isolation | Core |
| **Flyway** | Versioned, repeatable schema evolution | SQL-first, simple | Liquibase | P1 | Migration versioning, expand/contract | Core |
| **Spring Data JPA / Hibernate** + **JdbcClient** | Mapping aggregates; explicit SQL for hot paths | JPA for aggregate CRUD; JdbcClient where SQL is the point (inventory, queues) | jOOQ (excellent, typed SQL), MyBatis | P2 | Persistence context, dirty checking, fetch strategies, N+1 | Core |
| **Spring Modulith** | Module boundaries rot silently in monoliths | Verifies module dependencies in a test; documents modules | ArchUnit alone, Maven multi-module | P1 | Package conventions, named interfaces | Core |
| **Docker / Compose** | "Works on my machine"; reproducible dependencies | Standard; Compose is enough for local + small prod | Podman, Nix, devcontainers | P1 | Images, layers, volumes, networks, healthchecks | Core |
| **Testcontainers** | Tests against fake DBs miss real behaviour | Real PostgreSQL in tests | H2 (wrong semantics), shared test DB | P1 | Container lifecycle, singleton pattern | Core |
| **GitHub Actions** | Automated verification on every change | Integrated with GitHub, free tier, Docker available | GitLab CI, Jenkins | P1 | Workflows, caches, secrets, environments | Core |
| **Spring Security** + **Resource Server** | AuthN/AuthZ plumbing that is easy to get wrong | Mature, standard, integrates with JWT/OAuth | Keycloak (external IdP: great, but hides what we want to learn), Auth0 | P2 | Filter chain, `SecurityContext`, JWT validation | Core |
| **Spring Authorization Server** | OAuth2 provider for third-party apps | Spec-compliant, extensible | Keycloak, Ory Hydra | P12 | OAuth2 flows, PKCE, consent | Phase-gated |
| **OpenTelemetry** | Vendor-neutral telemetry | Standard; Boot 4 starter | Vendor agents | P6 (P1 has metrics/logs only) | Traces, spans, context propagation, OTLP | Core |
| **Prometheus** | Metrics storage + PromQL | Standard; pull-based; works with Grafana | VictoriaMetrics, Mimir | P1 (endpoint), P6 (server) | Counters/gauges/histograms, labels, cardinality | Core |
| **Grafana** | Dashboards & alerting across signals | Links metrics/logs/traces | Kibana, SigNoz UI, vendor UIs | P6 | Dashboards, alert rules | Core |
| **Loki** | Log aggregation indexed by labels | Cheap, Grafana-native, LogQL | Elasticsearch/OpenSearch, ClickHouse | P6 | Labels vs. content, LogQL | Core (via otel-lgtm) |
| **Tempo** | Trace storage | Cheap object-storage traces, TraceQL | Jaeger, Zipkin | P6 | Trace search | Core (via otel-lgtm) |
| **Mailpit** | Catching emails locally | Simple SMTP + UI | MailHog (unmaintained) | P2 | SMTP basics | Core (dev only) |
| **Redis** | Shared fast state across instances (rate limits, caches) | Simple, ubiquitous, Lua/atomic ops | Memcached (no data structures), PG-based limiter (slower, still valid) | P12 | Data types, TTL, atomicity, persistence modes, failure modes | Phase-gated |
| **Bucket4j** | Token-bucket rate limiting | In-memory first, Redis backend later | Resilience4j RateLimiter (per-instance only), custom Lua | P2 | Token bucket vs. fixed/sliding window | Core |
| **Resilience4j** | Circuit breakers, retries, bulkheads | Lightweight, Micrometer metrics | Spring Retry (retry only), Failsafe | P10 | The patterns in 21-reliability | Phase-gated |
| **k6** | Scripted load testing | JS scripts, thresholds, good output | Gatling, JMeter, Locust | P5 (small), P15 (suite) | Open vs. closed workload models, percentiles | Core |
| **WireMock / Toxiproxy** | Simulating external APIs / network faults | Deterministic contract tests; failure injection | MockServer, Pumba | P10 / P17 | — | Core in their phases |
| **Nginx / Traefik / Caddy** | TLS termination, routing, host-based routing | **Caddy** for P16 (automatic TLS incl. on-demand for custom domains); Traefik if on Docker labels/K8s | Nginx (manual certs, very capable) | P16 | Reverse proxy, TLS, headers | Phase-gated |
| **Object storage (MinIO → S3)** | Binary files don't belong in PG | S3 API is the standard; MinIO locally | Filesystem (doesn't scale across instances) | P13 | Presigned URLs, key design, lifecycle rules | Phase-gated |
| **RabbitMQ** | Broker for job distribution when the DB queue isn't enough | Simple queue semantics, DLX, good for task queues | SQS, NATS, Kafka | P18 if measured | AMQP, acks, prefetch, DLX | Optional |
| **Kafka** | Durable replayable event log at high volume | For an analytics/CDC stream only | Redpanda, Pulsar, Kinesis | P18 | Partitions, offsets, consumer groups, compaction | Optional |
| **OpenSearch** | Advanced search relevance and scale | Open-source Elasticsearch fork | Elasticsearch, Meilisearch, Typesense | P18 | Inverted indexes, analyzers, index sync | Optional |
| **Kubernetes** | Orchestrating many services declaratively | Industry platform | Nomad, ECS, Cloud Run | P18 | Pods, Deployments, Services, probes, resources | Optional |
| **Angular** | Frontends that exercise the APIs | Required by you; typed; batteries included | React, Vue | F1/F2 | Components, signals, HttpClient, routing | Core (tracks) |
