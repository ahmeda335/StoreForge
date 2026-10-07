# Failure Experiments

Planned catalogue: [blueprint/25-failure-experiments.md](../blueprint/25-failure-experiments.md). Each run is written up with [TEMPLATE.md](TEMPLATE.md).

| ID | Experiment | Phase | Result |
|---|---|---|---|
| [EXP-001](EXP-001-database-unavailable-p1.md) | Database unavailable (stop/start, start while down, graceful shutdown) | P1 | As expected; probe timeout must be > 3s |
| [EXP-009](EXP-009-smtp-outage-and-dropped-logs.md) | SMTP outage during registration (EXP-002 revisited) | P9 | Email delivered automatically after recovery; found and fixed dropped retry/DEAD log lines |
| [EXP-008](EXP-008-async-guarantees-mutations.md) | Mutations: no inbox, no SKIP LOCKED | P9 | Double count; job executed 4× (3/3 runs). Both caught by tests |
| [EXP-007](EXP-007-pay-vs-cancel-race.md) | Pay vs. cancel race with and without the order row lock | P8 | Both correct (side effects in-transaction); lock gives precise errors; mandatory once side effects are external |
| [EXP-006](EXP-006-duplicate-checkout-completion.md) | 20 concurrent completions with one key, with and without stored idempotency | P7 | Stored results → identical answers; without them, effect still once but 19 × 409 |
| [EXP-005](EXP-005-collector-unavailable.md) | Telemetry Collector restarted while the app was exporting | P6 | Requests unaffected; ~4s of telemetry lost; exporter ERROR logs (noise) |
| [EXP-004](EXP-004-oversell-three-ways.md) | Stock = 1, 50 buyers: naive vs. conditional update vs. SERIALIZABLE | P5 | Naive oversold 10× (= pool size); SERIALIZABLE 9 aborts; conditional exactly 1 |
| [EXP-003](EXP-003-write-skew-last-owner.md) | Write skew on the last-owner invariant (mutation: lock removed) | P3 | Lock prevents it; without the lock 5/5 runs end with zero owners |
| [EXP-002](EXP-002-email-provider-unavailable.md) | Email provider unavailable during registration | P2 | Registration succeeds; WARN + metric; link must be re-requested (P9 will retry) |
