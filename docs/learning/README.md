# Learning Notes

Each note teaches a technology or pattern **through this project**, using a fixed structure: Problem · Why we need it · Alternatives · Decision · Mental model · Project usage · Configuration · Failure cases · Testing · Observability · When NOT to use it.

Prerequisite map (what to study before each phase): [blueprint/27-learning-map.md](../blueprint/27-learning-map.md).

| Note | Introduced | Topic |
|---|---|---|
| [postgresql-roles-and-least-privilege](postgresql-roles-and-least-privilege.md) | P1 | PostgreSQL roles, schema ownership, default privileges, role-level timeouts |
| [flyway-migrations](flyway-migrations.md) | P1 | Versioned schema migrations |
| [docker-and-compose](docker-and-compose.md) | P1 | Images, multi-stage builds, Compose, init scripts |
| [testcontainers](testcontainers.md) | P1 | Real dependencies in tests |
| [health-probes](health-probes.md) | P1 | Liveness vs. readiness, graceful shutdown |
| [structured-logging-and-correlation](structured-logging-and-correlation.md) | P1 | JSON logs, MDC, request IDs, problem details |
| [spring-modulith](spring-modulith.md) | P1 | Enforcing module boundaries in a monolith |
| [password-hashing](password-hashing.md) | P2 | Argon2id, salts, upgradeable hashes, timing parity |
| [jwt-access-tokens](jwt-access-tokens.md) | P2 | JWT structure, RFC 8725 validation, RFC 9068 profile, revocation, key rotation |
| [refresh-tokens-cookies-and-csrf](refresh-tokens-cookies-and-csrf.md) | P2 | Rotation, reuse detection, atomic consumption, cookie flags, CSRF |
| [rate-limiting-lockout-and-enumeration](rate-limiting-lockout-and-enumeration.md) | P2 | Token buckets, lockout, account-enumeration resistance |
| [spring-security-filter-chain](spring-security-filter-chain.md) | P2 | How requests are authenticated; multiple chains per API |
| [row-level-security-and-tenant-context](row-level-security-and-tenant-context.md) | P3 | PostgreSQL RLS, `SET LOCAL` with pooled connections, directory vs. data tables |
| [rbac-and-anti-escalation](rbac-and-anti-escalation.md) | P3 | Roles, permissions, 404 vs. 403, subset rule |
| [write-skew-and-row-locks](write-skew-and-row-locks.md) | P3 | Set-level invariants under concurrency; materializing conflicts |
| [keyset-pagination](keyset-pagination.md) | P3 | Cursor pagination vs. OFFSET |
| [optimistic-locking-and-etags](optimistic-locking-and-etags.md) | P4 | `@Version`, ETag/If-Match, 412 vs. 409 |
| [n-plus-one-and-read-models](n-plus-one-and-read-models.md) | P4 | Query-count thinking, LATERAL aggregates, read models, the N+1 detector |
| [money](money.md) | P4 | Minor units, strict parsing, decimal strings on the wire |
| [postgresql-locking-and-conditional-updates](postgresql-locking-and-conditional-updates.md) | P5 | MVCC, EvalPlanQual, deadlocks and lock ordering, SKIP LOCKED, pool size as race width |
| [security-definer-functions](security-definer-functions.md) | P5 | Controlled privilege escalation, search_path hijacking, least privilege |
| [opentelemetry-and-distributed-tracing](opentelemetry-and-distributed-tracing.md) | P6 | Traces, spans, context propagation, Observation API, sampling, OTLP/Collector, log correlation |
| [transactional-outbox](transactional-outbox.md) | P9 | Dual-write problem, outbox, relay, thin events, idempotent consumers, inbox |
| [postgres-job-queue](postgres-job-queue.md) | P9 | SKIP LOCKED claims, leases, backoff + jitter, dead letters, queue metrics |
| [lombok](lombok.md) | P8→P9 | What the four allowed Lombok annotations generate, and why the others are banned on domain classes |
| [state-machines-and-gapless-counters](state-machines-and-gapless-counters.md) | P8 | Transition methods + CHECKs, orthogonal states, counter rows vs. sequences |
| [idempotency](idempotency.md) | P7 | Idempotency keys, fingerprints, effect vs. response idempotency, concurrency of duplicates |
| [metrics-types-and-cardinality](metrics-types-and-cardinality.md) | P6 | Counters/gauges/histograms, `rate()`, `histogram_quantile`, RED, cardinality |
