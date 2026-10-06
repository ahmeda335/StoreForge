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
