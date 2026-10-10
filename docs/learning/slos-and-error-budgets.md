# SLOs, Error Budgets and Burn-Rate Alerts

**Problem.** "Is it fast enough?" and "should someone wake up?" need numbers everyone agrees on. Threshold alerts ("p95 > 200 ms for 5 min") page on blips and miss slow leaks.

**Why we need it.** P15: the load tests need pass/fail criteria, and P16 production needs alerts that mean something.

**Alternatives.**
- **Static thresholds per metric;**
- **SLOs with error budgets and burn-rate alerts** (Google SRE workbook).

**Decision.** SLOs per traffic type ([slos.md](../performance/slos.md)), used as k6 thresholds, with multi-window burn-rate alerts ([alerting.md](../performance/alerting.md)).

**Mental model.**
- **SLI** (what you measure): share of good events, e.g. requests under 200 ms, or not 5xx. **SLO** (the target): 95 % / 99.9 % over 30 days.
- **Error budget** = 1 − SLO. It's the amount of failure you're allowed, and it can fund risk (deploys, experiments).
- **Burn rate** = how fast the budget is spent; 1× lasts exactly 30 days.
  - Fast burn (14.4× over 1 h, confirmed over 5 min) pages: 2 % of the month is gone in an hour.
  - Slow burn (6× over 6 h, confirmed over 1 h) opens a ticket.
- **Exclude what isn't failure:** 429 is the system working as designed.
- **Exact SLIs need histogram buckets at the thresholds** (`slo: 200ms, 300ms, 500ms`); otherwise you interpolate.
- **Test alert rules like code:** synthetic series must fire, healthy ones must stay quiet (`promtool test rules`).

**Project usage.** `infra/prometheus/slo-rules.yml` and its tests (CI job `slo-rules`), `management.metrics.distribution.slo`, k6 thresholds in `load/k6/mixed.js`, [runbook](../operations/runbooks/slo-burn.md).

**Failure cases.**
- Objectives nobody can meet, or that are never met anyway;
- alerting on causes (CPU) instead of symptoms (users waiting);
- one window only (flapping, or slow to resolve);
- counting rate limiting as unavailability.

**Testing.** promtool unit tests. While writing them, a 50 % slow rate didn't fire a fast-burn alert: correct, since the threshold is 72 %; the test data was wrong, not the rule.

**Observability.** The recorded ratios per API family; the k6 summary per run.

**When NOT to use it.** Low-traffic endpoints (a few requests per hour) make ratios meaningless: use synthetic checks instead.
