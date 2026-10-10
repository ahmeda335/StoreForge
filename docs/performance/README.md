# Performance (P15)

- **Objectives:** [slos.md](slos.md). **Alerting:** [alerting.md](alerting.md) (burn rates, rules tested with promtool).
- **How to measure:** [load/README](../../load/README.md) (seed, k6 mix, run script, profiler).
- **Results and fixes:** [EXP-023](../experiments/EXP-023-load-baseline.md), [ADR-030](../decisions/ADR-030-bounded-concurrency-and-virtual-threads.md).

## Baseline (final configuration, shared 12-core laptop)

| Load | Requests/s | Storefront p95 / p99 | Checkout p95 | Admin p95 | Developer p95 | Failed | Within SLOs |
|---|---|---|---|---|---|---|---|
| SCALE 1 | 173 | 36 / 63 ms | 28 ms | 17 ms | 16 ms | 0 % | yes |
| SCALE 2 | 341 | 201 / 346 ms | 250 ms | 297 ms | 392 ms | 1.3 % shed | just over (storefront, developer) |
| SCALE 3 | 503 | 224 / 361 ms | 201 ms | 328 ms | 423 ms | 6 % shed | no |
| SCALE 5 | 754 | sheds the excess (503); stays healthy | | | | 56 % shed | no |

Before P15's fixes, SCALE 5 deadlocked the instance permanently.

## Measured improvements
| # | Change | Before → after |
|---|---|---|
| 1 | Store lookup outside the cache's lock (deadlock) | SCALE 5: hung instance → serving; health answers |
| 2 | Load shedding, 64 in flight | SCALE 5: 252 → 754 req/s, 36k → 0 dropped, 1,432 → 0 full GCs; SCALE 3 storefront p95 3,029 → 224 ms |
| 3 | Product-by-handle on a text index under RLS (V20) | 3.67 ms (scan) → 0.05 ms |
| 4 | Stored normalized title for search (V21) | match 37.5 → 13.7 ms; 8.8 M → 0 unaccent calls; −29 % connection time |

Where the time still goes: search is the dominant database cost (ranking every match of common words, facets over all matches); the database CPU is the bottleneck, not the pool size (pool 20 didn't help).
