# Fault Injection, Steady State and Failure Semantics

**Problem.** Resilience code (pools, timeouts, fallbacks, breakers, leases, alerts) is mostly untested until the day it's needed. Unit tests check one component's reaction; they don't show what users see, what on-call sees, or whether the system comes back.

**Why we need it.** P17: turn "we think it's resilient" into measured behaviour on a production-like stack, and fix what the measurements show.

**Alternatives.**
- **Wait for incidents:** real, but expensive, and usually missing the "before" data.
- **Component tests with fakes:** fast and precise, but they never show the interaction (pool + driver + proxy + alert).
- **Game days / chaos experiments on staging:** a fault injected under steady traffic, with a hypothesis and an abort plan.
- **Chaos in production** (Netflix-style): the most realistic, but it needs mature isolation and blast-radius control; not for a single host.

**Decision.** Scripted experiments on staging ([deploy/chaos/](../../deploy/chaos/README.md), [EXP-025](../experiments/EXP-025-failure-suite-on-staging.md)). Each one has:
- steady traffic;
- a fault window;
- a breakdown of failures by kind;
- data invariants;
- a heal-on-abort.

Fixes follow [ADR-032](../decisions/ADR-032-failure-semantics.md).

**Mental model.**
- **An experiment is a hypothesis about the steady state.** Define "normal" first (here: browse 3/s and checkouts 6/min, all succeeding, under every rate limit). Then predict what changes during the fault and what must not. Anything outside the prediction is a finding, including "nothing happened" when something should have, like a page.
- **Three questions per fault:**
  - What do users get? A status, how fast, and is it retryable?
  - What does on-call get? Alerts, log level and volume.
  - Does it come back, and how long after the dependency does?
- **Measure recovery from the dependency's recovery,** not from your heal command. PostgreSQL itself needed 3 s or 17 s of crash recovery; the app's part was 2–3 s after it.
- **Failure semantics:**
  - **5xx splits in two:** "we have a bug" (500) and "a dependency is temporarily unavailable" (503 + `Retry-After`);
  - a retry is only safe if the failed attempt left no partial effect (one transaction, stored idempotency rolled back with it);
  - **WARN for dependencies, ERROR for bugs,** or on-call learns to ignore ERROR.
- **Exceptions get rewrapped:** the useful signal (SQLSTATE `08…`) can be deep in the cause chain, or replaced entirely: a rollback failure can mask the original error. Classify by walking the chain, and test with realistic wrapping.
- **Slow is harder than down.**
  - A down dependency fails fast once timeouts are bounded.
  - A slow one keeps every request in flight longer: in E3, 200 ms per round trip turned into > 1 s per request. That fills pools and queues, and only load shedding and timeouts stop the spread.
- **Timeouts are layered:**
  - the pool's wait for a connection (Hikari `connection-timeout`);
  - the driver's connect, login and socket timeouts;
  - the server's `statement_timeout` and `lock_timeout`;
  - the client's timeout.
  - A missing layer is invisible until the matching fault happens.
- **Alert maths has edge cases:**
  - a ratio over zero requests is NaN, and NaN poisons every average that includes it;
  - a budget of 5 % allows at most a 20× burn, so a rule written for a 0.1 % budget can't fire in time;
  - test alert rules with idle periods and total failures, not only typical traffic.

**Project usage.**
- [run.sh](../../deploy/chaos/run.sh): inject, heal, report, invariants.
- [traffic.js](../../deploy/chaos/traffic.js): the steady state.
- Toxiproxy via [compose.chaos.yaml](../../deploy/chaos/compose.chaos.yaml).
- [`ApiExceptionHandler`](../../src/main/java/dev/storeforge/shared/web/ApiExceptionHandler.java), [`StorageUnavailableException`](../../src/main/java/dev/storeforge/storage/StorageUnavailableException.java).
- Driver timeouts in `application.yml`.
- [slo-rules.yml](../../infra/prometheus/slo-rules.yml) with promtool tests.

**Failure.**
- **Fault left behind:** an experiment that dies mid-fault leaves staging broken. The runner traps INT, TERM and ERR and heals first.
- **Signals in background jobs:** a non-interactive shell starts background jobs with SIGINT *ignored*, and a trap can't catch it. Bash also runs traps only after a foreground `sleep` finishes; `sleep & wait` fixes that.
- **The load generator as the fault:** one address above the per-address limits produces 429s that look like an outage (P16). Keep steady traffic under every limit.
- **Contaminated windows:** results that depend on alert windows (1 h, 6 h) carry over between back-to-back runs. Note the history, or wait it out.

**Testing.**
- Every fix has a fast test: MockMvc through the real handler with realistic exception chains; the S3 adapter against an unreachable port and a tiny HTTP server answering 502 and 403; promtool tests for the idle and total-slowdown cases.
- Mutation checks for each rule.
- Then the experiment is rerun on the deployed fix.

**Observability.**
- Failures by kind and step over time (k6 JSON points), against inject and heal timestamps.
- `hikaricp_connections` and `_pending`; `storeforge_ratelimit_decisions_total{backend}`; the recorded SLO ratios; `pg_stat_archiver`.
- Alertmanager and Mailpit for what on-call actually received.

**When NOT to use it.**
- Against shared or production systems without isolation and a rollback.
- Without a defined steady state: then there is nothing to compare against.
- To "prove" resilience from one run: repeat the runs, and compare against the dependency's own recovery.
- For logic that a deterministic test can cover (races, idempotency): use the fast test, and keep experiments for the interactions.
