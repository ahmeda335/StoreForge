# Load Testing: Open vs. Closed Models, and Reading the Results

**Problem.** A load test that slows down when the system slows down measures the test, not the system. Averages hide the users who wait. Results on a busy laptop drift from run to run.

**Why we need it.** P15: baselines, capacity, and before/after numbers for every optimisation.

**Alternatives.**
- **Closed model:** N users, each waiting for its previous response. The arrival rate drops when the system is slow (coordinated omission).
- **Open model:** requests arrive at a fixed rate whatever happens, like real traffic.
- **Replaying production logs:** realistic, but needs production first.

**Decision.** k6 `constant-arrival-rate` scenarios (open), a realistic mix and data shape, the SLOs as thresholds, statement statistics and app metrics stored with every run ([load/README](../../load/README.md)).

**Mental model.**
- **Open model:** if the system can't keep up, k6 reports `dropped_iterations` and latency rises; it doesn't quietly send less.
- **Percentiles, not averages:**
  - p50 is the typical request; p95/p99 are the unlucky ones, who are often your busiest customers;
  - compare p99 under load with p99 at idle;
  - a max of exactly 60 s means k6's request timeout, not a measurement.
- **Shape matters:** tenants differ (one large store gets 40 % of traffic), data has history (400k orders), and clients are many addresses (per-address limits).
- **Find the cliff, not just the knee:** step the load (SCALE 1, 3, 5) until it breaks, then look at how it breaks. Collapse and graceful degradation are very different failures (EXP-023).
- **Little's law:** in-flight requests = throughput × latency. It tells you how much concurrency is useful and how much is just queue.
- **Paired runs:** on a shared machine, compare runs taken minutes apart, and say so.

**Project usage.** `load/k6/mixed.js`, `load/run.sh` (k6 summary, `pg_stat_statements`, Hikari/JVM metrics per run), [EXP-023](../experiments/EXP-023-load-baseline.md).

**Failure cases.**
- Closed-model tests that never overload;
- one client address hitting your own rate limits (first smoke run);
- seeding with unanalyzed temp tables (minutes instead of seconds);
- concluding from a single run on a noisy machine.

**Testing.** The thresholds are the test: a run passes or fails on the SLOs.

**Observability.** `pg_stat_statements` (reset per run), `hikaricp_connections_acquire_seconds` (waiting for a connection) vs `usage_seconds` (holding one), GC pauses, in-flight and shed counters.

**When NOT to use it.** Before the data and traffic shape are realistic, the numbers mislead. For a single slow request, use EXPLAIN and a profiler first.
