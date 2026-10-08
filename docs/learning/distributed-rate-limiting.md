# Distributed Rate Limiting with Redis (and failing open)

**Problem.** An API budget ("40 requests, then 2 per second") enforced in each instance's memory is really N × 40 with N instances behind a load balancer. One integration can't be allowed to consume capacity meant for all, and a store's many integrations together need a cap too.

**Why we need it.** The developer API (P12). Later, storefront protection (P13) and plan quotas.

**Alternatives.**
- **In-memory per instance:** simple, but wrong with more than one instance. We kept it for auth endpoints in P2, where the per-email lockout carries the real protection.
- **The database:** correct, but a write per request on the primary.
- **An API gateway's limiter:** good at the edge, but blind to credentials and stores unless we teach it.
- **Fixed windows:** bursty at window edges.

**Decision.** Token buckets in Redis (Bucket4j, compare-and-swap scripts), one per credential and one per store. Fall back to per-instance buckets when Redis is unavailable ([ADR-020](../decisions/ADR-020-redis-fail-open.md)).

**Mental model.**
- **Token bucket:** capacity = burst, refill rate = sustained throughput. Each request takes a token. When there's none left: 429 with `Retry-After` = time until the next token.
- **Shared state makes the limit real.** Every instance consults the same bucket, atomically: a Lua script reads, computes and writes in one step.
- **Hierarchical limits:** take from the credential's bucket, then the store's. If the store refuses, give the credential's token back.
- **Decide the failure mode before the outage.**
  - *Fail closed:* refuse everything. Right for hard quotas and money.
  - *Fail open:* keep serving with weaker limits. Right when the limit is soft and the API is valuable.

  Ours fails open to local buckets: limits become N× looser, but never disappear.
- **Don't make every request pay for a dead dependency.** Short command timeouts, "reject while disconnected" instead of queueing, and a circuit breaker that stops calling Redis for a while.
- **Report it honestly:** DEGRADED, not DOWN. The app works; a monitor shouldn't call it dead.
- **Tests that count tokens must respect time:** buckets refill while the test runs. Drain *until refused*, and assert ranges when refill is part of the behaviour.

**Project usage.** `ApiRateLimiter`, `RedisConnections`, the `redis` circuit breaker in `DeveloperConfiguration`, `UsageCounters` (same Redis, same fail-open), `DeveloperApiInterceptor`.

**Failure cases.**
- A limiter in memory with multiple instances;
- Lettuce's default queueing while disconnected (requests hang);
- no breaker (each request waits for a timeout);
- health DOWN for an optional dependency ([EXP-014](../experiments/EXP-014-redis-outage.md));
- fixed-count tests on refilling buckets ([EXP-016](../experiments/EXP-016-developer-platform-mutations.md)).

**Testing.** `RateLimitIT`: burst; shared budget (a second limiter instance on the same Redis spends it); store-wide cap; frozen Redis (container paused) → serving, limited locally, fast, DEGRADED, recovery. EXP-014 repeats this with two real instances and a stopped Redis.

**Observability.**
- `storeforge_ratelimit_decisions_total{backend=redis|local, outcome}`;
- `resilience4j_circuitbreaker_state{name="redis"}`;
- `X-RateLimit-*` headers;
- `rate_limited` in hourly usage;
- `storeforge_usage_dropped_total`.

**When NOT to use it.** Single-instance apps (in-memory is fine). Limits that must be exact under all failures, such as billing: count durably instead.
