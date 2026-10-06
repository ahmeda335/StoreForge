# 17 — Testing Strategy

Tests exist to protect **behaviour and invariants**, not coverage numbers. The question for every test is: *which bug would this catch that nothing else catches?*

```text
            ▲ few, slow, broad
      Load (k6) / Chaos experiments
      E2E (critical flows via HTTP, full stack in Compose)
      Contract (webhook payload schemas, provider adapter vs. recorded API)
      API tests (MockMvc/RestTestClient: auth, validation, error format)
      Integration (Spring + real PostgreSQL via Testcontainers)
      Unit (domain: value objects, state machines, pricing)
            ▼ many, fast, narrow
```

## Levels

| Level | Tooling | What it covers | Examples |
|---|---|---|---|
| Unit | JUnit 5, AssertJ | Pure logic with no Spring | `Money` arithmetic & rounding; payment state machine rejects `REFUNDED → CAPTURED`; checkout totals; discount evaluation; request-id validation; backoff calculation |
| Integration | `@SpringBootTest` + **Testcontainers PostgreSQL** (real DB, never H2: H2 has no RLS, `SKIP LOCKED` semantics, partial indexes, or `citext`) | Repositories, constraints, migrations, transactions, RLS | Flyway migrates on an empty DB; unique violations map to 409; RLS hides other stores; outbox row written in the same TX |
| API | MockMvc / `RestTestClient` against the full Spring context | HTTP contract: status codes, problem-details format, validation, authentication (401), authorization (403/404), pagination | Every admin endpoint returns 404 for another store's resource; malformed JSON → 400 problem |
| Contract | JSON Schema assertions on webhook payloads; adapter tests against recorded Stripe responses (WireMock) | Our promises to third parties; our assumptions about providers | `orders/create` payload for API version 2026-10 matches the published schema; Stripe adapter handles a `card_declined` response |
| E2E | Black-box HTTP tests against the Docker Compose stack (CI job) | The business flow works with real wiring | Register → create store → product → stock → cart → checkout → fake payment webhook → order PAID → webhook received by a test receiver |
| Concurrency | JUnit + `ExecutorService`/`CountDownLatch` against Testcontainers PG | Invariants under parallel execution | 100 threads buy the last item → exactly 1 order, stock 0, 99 × 422; two refunds racing never exceed captured; same idempotency key ×20 concurrently → one order |
| Security | API tests + dedicated suites | Bypass attempts | Missing/expired/forged JWT (`alg=none`, wrong key, wrong `aud`); IDOR matrix across tenants; privilege escalation (staff granting owner); rate limits trigger 429; secrets not in logs |
| Load | **k6** | Throughput/latency under realistic mixes | See below |

## Tenant isolation suite (permanent, grows with every module)

1. Fixture: stores A and B with one of every resource.
2. For every endpoint (discovered from the request-mapping registry so new endpoints are included automatically), call as A with B's ids → expect 404/403, and assert B's identifiers never appear in response bodies.
3. RLS meta-test: every table with `store_id` has RLS enabled and forced, plus a policy.
4. Worker test: a job for store A cannot read B's rows.

## Concurrency testing rules

- Use a real database, real transactions and real connection pool sizes.
- Start threads at the same instant (latch). Repeat the test (e.g. `@RepeatedTest(20)`), because races are probabilistic.
- Assert **invariants** after the storm (sum of ledger = levels; no negative stock; order count == successful responses), not just "no exception".

## Load-test scenarios (k6)

| Scenario | Shape | Watches |
|---|---|---|
| Storefront browse | 80% product list/detail, 20% search; ramp to N rps | p95 latency, DB CPU, cache hit rate |
| Checkout mix | browse → cart → checkout → complete with fake provider | Checkout SLO, pool saturation, lock waits |
| Flash sale | 500 VUs buy one variant with 50 units | Exactly 50 orders; reservation failures counted; no deadlocks; latency under contention |
| Noisy neighbour | Store A at 10× traffic, store B normal | B's latency unaffected; A gets 429s |
| Webhook storm | 10k events with slow receivers (WireMock delay) | Queue age, dispatcher isolation, no impact on HTTP latency |
| Soak | Moderate load for 2h | Memory leaks, connection leaks, table bloat (jobs/outbox) |
| Spike | 0 → 5× in 30 s | Autoscaling (later), queueing, error rate |

Each k6 run records: commit SHA, dataset size, environment and resource limits. Results go to `docs/testing/load-results/`. Comparisons without this context are meaningless.

## Test data & hygiene

- Test data builders (`aProduct().withVariants(3)`), not shared SQL fixtures that couple tests.
- Each integration test creates its own stores (random handles), so **no ordering dependencies** and no DB truncation between tests is needed. A singleton Testcontainers container is reused across test classes for speed.
- Clock injected (`java.time.Clock`) so expiry logic is testable without sleeps.
- Flaky test = bug. Quarantine with an issue, never `@Disabled` silently.
