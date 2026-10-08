# Failure Experiments

Planned catalogue: [blueprint/25-failure-experiments.md](../blueprint/25-failure-experiments.md). Each run is written up with [TEMPLATE.md](TEMPLATE.md).

| ID | Experiment | Phase | Result |
|---|---|---|---|
| [EXP-001](EXP-001-database-unavailable-p1.md) | Database unavailable (stop/start, start while down, graceful shutdown) | P1 | As expected; probe timeout must be > 3s |
| [EXP-016](EXP-016-developer-platform-mutations.md) | Mutations: store binding, scopes, anti-escalation, OAuth re-checks, stored tokens, PKCE | P12 | Found a test gap (scope escalation via OAuth), a usage-counting bug (rejected requests uncounted) and flaky refill-sensitive tests; D1 showed controllers can't leak other stores even without the binding |
| [EXP-015](EXP-015-third-party-app-lifecycle.md) | StockSync's whole life: install, events, restock, burst with backoff, uninstall | P12 | Works end to end across two processes; audit shows APP actor |
| [EXP-014](EXP-014-redis-outage.md) | Redis stops under two instances | P12 | Shared budget 42/60 → per-instance 60/60 at p50 14 ms → 41/60 after recovery; found health 503 → DEGRADED |
| [EXP-013](EXP-013-webhook-mutations.md) | Mutations: redirects, plain DNS, no deadline, no bulkhead, no rotation overlap | P11 | All caught after fixing two test bugs in the deadline test (wrong row, wrong threat model) |
| [EXP-012](EXP-012-head-of-line-blocking.md) | One slow webhook receiver vs. the job worker (2 instances) | P11 | Email 32.8 s → 0.2 s, 4 duplicate webhooks → 0 after concurrent worker + bulkheads; found a payments cancellation bug |
| [EXP-011](EXP-011-lost-webhooks.md) | Webhooks never arrive (real HTTP delivery broken) | P10 | Reconciliation paid the order after 38 s; found and fixed a reconciliation run aborted by one unknown payment |
| [EXP-010](EXP-010-payment-mutations.md) | Mutations: no refund lock, non-idempotent capture, random provider key, no compensation | P10 | All caught, after closing a test gap (re-sent webhook under a new event id) |
| [EXP-009](EXP-009-smtp-outage-and-dropped-logs.md) | SMTP outage during registration (EXP-002 revisited) | P9 | Email delivered automatically after recovery; found and fixed dropped retry/DEAD log lines |
| [EXP-008](EXP-008-async-guarantees-mutations.md) | Mutations: no inbox, no SKIP LOCKED | P9 | Double count; job executed 4× (3/3 runs). Both caught by tests |
| [EXP-007](EXP-007-pay-vs-cancel-race.md) | Pay vs. cancel race with and without the order row lock | P8 | Both correct (side effects in-transaction); lock gives precise errors; mandatory once side effects are external |
| [EXP-006](EXP-006-duplicate-checkout-completion.md) | 20 concurrent completions with one key, with and without stored idempotency | P7 | Stored results → identical answers; without them, effect still once but 19 × 409 |
| [EXP-005](EXP-005-collector-unavailable.md) | Telemetry Collector restarted while the app was exporting | P6 | Requests unaffected; ~4s of telemetry lost; exporter ERROR logs (noise) |
| [EXP-004](EXP-004-oversell-three-ways.md) | Stock = 1, 50 buyers: naive vs. conditional update vs. SERIALIZABLE | P5 | Naive oversold 10× (= pool size); SERIALIZABLE 9 aborts; conditional exactly 1 |
| [EXP-003](EXP-003-write-skew-last-owner.md) | Write skew on the last-owner invariant (mutation: lock removed) | P3 | Lock prevents it; without the lock 5/5 runs end with zero owners |
| [EXP-002](EXP-002-email-provider-unavailable.md) | Email provider unavailable during registration | P2 | Registration succeeds; WARN + metric; link must be re-requested (P9 will retry) |
