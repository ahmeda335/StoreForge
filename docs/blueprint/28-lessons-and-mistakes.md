# 28 — Engineering Lessons and 29 — Common Mistakes

## Lessons

**Architecture**
1. Don't introduce distributed systems before you understand the domain. Boundaries drawn too early are drawn wrong.
2. Don't use microservices because they look impressive. Every network hop is a new failure mode.
3. Prefer explicit boundaries: a module's public API is a decision, not an accident.
4. Make the dependency direction boring and one-way. Cycles are the start of a big ball of mud.
5. An abstraction needs two real implementations or a real test seam.
6. Name things after the business (`Checkout`, `Reservation`), not the pattern (`OrderManagerFactory`).

**Data and correctness**
7. Database constraints are part of business correctness. The app *will* have bugs; the constraint is the last line.
8. Prefer making invalid states unrepresentable (value objects, state machines, CHECKs) over checking everywhere.
9. Short transactions: lock late, commit early, never wait on the network inside one.
10. Store money as integers in minor units. Never `double`.
11. Snapshot what must not change retroactively (order line prices).
12. Time is an input: inject a `Clock`.

**Distributed reality**
13. Treat external APIs as unreliable: every call needs a timeout, a retry policy and an idempotency strategy.
14. Assume requests are duplicated (double clicks, client retries, proxies).
15. Assume messages are duplicated and reordered.
16. Assume workers crash at the worst moment. Ask "what if the process dies on this line?"
17. A timeout means *unknown*, not *failed*.
18. Every asynchronous operation needs a failure strategy: retry, dead-letter, alert, a human action.
19. Make retries safe before making them automatic.

**Security**
20. Never trust the client: prices, totals, store ids, roles, quantities.
21. Tenant isolation must be enforced server-side, in depth, and tested continuously.
22. Authentication proves identity; authorization is checked per resource.
23. Secrets are hashed when you only verify them, encrypted when you must use them, and never logged.
24. Return 404 for other tenants' resources; don't confirm existence.

**Operations**
25. Observability is designed alongside features. If you can't see it, you can't operate it.
26. Measure before optimizing. Intuition about bottlenecks is usually wrong.
27. Alert on symptoms users feel; investigate causes with dashboards.
28. Liveness ≠ readiness. A DB outage should not restart your fleet.
29. Every migration must work with the previous app version (rolling deploys and rollbacks).
30. Run failure experiments on purpose. Resilience is a claim until tested.
31. Write the ADR when you make the decision, not when you've forgotten why.
32. A flaky test is a bug report you haven't read yet.

## Common mistakes: how to recognize them

| Mistake | How you'll recognize it | Prevention |
|---|---|---|
| Premature microservices | You're writing HTTP clients between your own modules; a feature needs 3 deploys | Modular monolith + Modulith verification |
| Overusing Kafka | Kafka in Compose with one producer and one consumer in the same app | DB queue until measured otherwise |
| Overusing Redis | Caching data that's cheap to query; bugs from stale data; Redis outage takes down features | A cache needs a metric showing need + an invalidation story |
| Incorrect transaction boundaries | `LazyInitializationException`; partial writes after an exception; `@Transactional` on private/self-invoked methods doing nothing | Transactions on application services only; integration tests that assert rollback |
| Remote calls inside transactions | Pool exhaustion when the provider is slow | Review rule + experiment #3 |
| Missing indexes | Seq Scan in `EXPLAIN`; latency grows with data | Index review per query; seed realistic data |
| N+1 queries | Many identical SQL spans in one trace | Query-count assertions in tests |
| Poor tenant isolation | Any query without `store_id`; any `findById(id)` on tenant data | StoreId-in-signature rule, RLS, isolation suite |
| JWT misuse | Permissions inside long-lived tokens; tokens in localStorage; no `aud` check | ADR-003 rules + forged-token tests |
| Missing authorization checks | Endpoint works for any logged-in user | Security tests per role; checks in services |
| Non-idempotent payment handling | Double charges in logs; duplicate `PaymentCaptured` | Idempotency keys + inbox + experiment #4/#5 |
| Ignoring webhook duplication | Two emails per order | Inbox / event-id dedup |
| Logging sensitive information | `Authorization:` or `password` in log search | Redaction + log-scan test |
| No correlation IDs | Can't connect a user's error report to logs | RequestIdFilter from P1 |
| No observability | Debugging by adding print statements in prod | Signals in every DoD |
| Testing only the happy path | All tests pass, production double-sells | Concurrency, failure and security suites |
| Overengineering abstractions | Interfaces with one implementation everywhere; mappers between identical classes | The "two implementations" rule |
| Using H2 for tests | Tests pass, PG migration fails | Testcontainers only |
| Offset pagination at scale | Page 1000 slow; duplicates when data changes | Keyset pagination |
| Swallowing exceptions | Silent data loss; "it just didn't happen" | Problem details + ERROR logs + no empty catches |
| Session-level `SET` with pooled connections | Requests occasionally see the previous tenant's context | `SET LOCAL` only, test with pool size 1 |
| Retry storms | Dependency recovers then falls over again | Backoff + jitter + retry budgets |
