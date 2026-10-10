# SLO alerting: error-budget burn rates (P15)

Rules: [`infra/prometheus/slo-rules.yml`](../../infra/prometheus/slo-rules.yml), tested with `promtool test rules infra/prometheus/slo-rules.test.yml` (a burn fires, a healthy API stays quiet). Objectives: [slos.md](slos.md). Runbook: [slo-burn](../operations/runbooks/slo-burn.md). Routing to people (email/chat) is part of P16.

## Why burn rates, not thresholds
"p95 > 200 ms for 5 minutes" pages on every short blip and misses a slow leak. An **error budget** turns the objective into an amount that can be spent:
- availability: 0.1 % of requests may fail per 30 days;
- latency: 5 % may be slower than the threshold.

The **burn rate** is how fast that budget is being spent: 1× uses it up in exactly 30 days.

| Alert | Condition (long window AND short window) | Means | Severity |
|---|---|---|---|
| `AvailabilityBudgetFastBurn` | 1 h and 5 min above **14.4×** | 2 % of the monthly budget gone in one hour | page |
| `LatencyBudgetFastBurn` | 15 min and 5 min with more than **50 %** of requests slow | latency budgets are large (5 %): at most 20× burn, so 14.4× over 1 h would take ~43 min to page a total slowdown (EXP-025) | page |
| `*SlowBurn` | 6 h and 1 h above **6×** | 5 % gone in six hours | ticket |

The short window makes the alert resolve soon after the problem stops; the long one keeps blips from paging.

## SLIs as recorded
- `storeforge:error_ratio:*`: 5xx / all requests (429 excluded: refusing abuse isn't unavailability), per API family (`storefront`, `admin`, `api`, `platform`, `auth`).
- `storeforge:slow_ratio:*`: requests slower than 200 ms (storefront) or 300 ms (admin, developer API) / all. Exact thanks to histogram buckets at the thresholds (`management.metrics.distribution.slo.http.server.requests: 200ms, 300ms, 500ms`).

## What a fast-burn page needs to know
Which API, since when (dashboard), what changed (deploys, traffic shape), and which statements got slower (`pg_stat_statements`). The [runbook](../operations/runbooks/slo-burn.md) walks through it.
