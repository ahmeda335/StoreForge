# Failure Experiments

Planned catalogue: [blueprint/25-failure-experiments.md](../blueprint/25-failure-experiments.md). Each run is written up with [TEMPLATE.md](TEMPLATE.md).

| ID | Experiment | Phase | Result |
|---|---|---|---|
| [EXP-001](EXP-001-database-unavailable-p1.md) | Database unavailable (stop/start, start while down, graceful shutdown) | P1 | As expected; probe timeout must be > 3s |
| [EXP-003](EXP-003-write-skew-last-owner.md) | Write skew on the last-owner invariant (mutation: lock removed) | P3 | Lock prevents it; without the lock 5/5 runs end with zero owners |
| [EXP-002](EXP-002-email-provider-unavailable.md) | Email provider unavailable during registration | P2 | Registration succeeds; WARN + metric; link must be re-requested (P9 will retry) |
