# ADR-032: Failure semantics: dependency outages answer 503 + Retry-After; bounded driver timeouts; alerts that survive idle time and total slowdowns

- **Status:** Accepted
- **Date:** 2026-10-10
- **Phase:** P17
- **Evidence:** [EXP-025](../experiments/EXP-025-failure-suite-on-staging.md)

## Context
P17 injected failures into staging under steady traffic. The system kept its data correct in every run. But it **described** failures wrongly:
- a database outage, a lock wait cut by `statement_timeout` and an object-storage outage all answered **500 "unexpected error"**, with ERROR stack traces;
- the pool took ~41 s to recover after PostgreSQL was back;
- two alerting rules could not fire when needed: a total slowdown would page only after ~43 min, and any idle stretch disabled the pages for an hour.

## Problem
1. What does the API answer when a dependency fails, and how is it logged?
2. How long may a connection attempt to the database take?
3. How do the SLO alerts treat "no traffic" and "everything slow"?

## Options Considered
1. **Status for dependency failures:**
   - 500 (retry decisions left to clients);
   - **503 + `Retry-After`** for failures that are temporary and safe to retry;
   - 504 for timeouts.
2. **Where to classify:**
   - every call site;
   - **one place per boundary**: the web exception handler for the database, the storage adapter for S3.
3. **Driver timeouts:**
   - rely on Hikari's `connection-timeout` (it bounds the wait for a connection, not the driver's attempt to create one);
   - **explicit driver `connectTimeout` / `loginTimeout` / `socketTimeout`**.
4. **Idle periods in the SLO ratios:**
   - record 0 (claims "no errors" without evidence);
   - **record nothing** (averages skip missing samples);
   - `avg_over_time` over sums instead of ratios.

## Decision
- **503 `service_unavailable` + `Retry-After: 5`** for these database failures, all logged at **WARN**:
  - no connection / no transaction;
  - statement or lock timeout, deadlock;
  - a lost connection: SQLSTATE class 08 anywhere in the cause chain, or HikariCP's "Connection is closed" from a rollback on an evicted connection.
- **503 `storage_unavailable` + `Retry-After: 30`** from the S3 adapter for network errors and 5xx. 4xx (e.g. 403 = our credentials or policy) stay 500.
- Every other unexpected failure stays **500 + ERROR**.
- **PostgreSQL driver:** `connectTimeout` 3 s, `loginTimeout` 5 s, `socketTimeout` 30 s (above the app role's `statement_timeout` of 10 s), TCP keepalive.
- **SLO rules:**
  - ratios are recorded only when there were requests (`/ (requests > 0)`);
  - the latency page fires when **more than 50 % of requests are slow over 15 min and 5 min**;
  - the latency ticket keeps the burn-rate recipe (6× over 6 h and 1 h).

## Why We Chose It
- **503 means "temporarily unavailable, try again".** It is true for every case listed, and safe: each failure rolls back the whole transaction, including the stored idempotency result. Proxies, SDKs and k6 treat 500 and 503 differently. On-call should see a dependency at WARN, not a fake bug at ERROR.
- **504** describes a gateway; the client isn't talking to one.
- **Classifying at the boundary** keeps call sites clean and makes the rule testable in one place: a MockMvc test through the real handler, and a test with an unreachable and a misbehaving S3 endpoint.
- **Driver timeouts:** without them, one stuck attempt can stall the pool's single connection-creator thread. Measured: recovery fell from ~41 s to 2–3 s after the database was ready, in three runs.
- **No sample instead of NaN:** one NaN sample poisons every average over a window that contains it. Missing samples are skipped, which is the right meaning: no requests means no evidence.
- **Latency page threshold:** a 5 % budget allows at most a 20× burn. So "14.4× over 1 h" needs 43 minutes of total slowdown before it pages, and only a near-total slowdown should page fast anyway.

## Trade-offs
- **Retries add load.** A 503 invites retries during an outage. `Retry-After` paces well-behaved clients; load shedding (P15) and rate limits bound the rest.
- **Hikari's message text.** The evicted-connection case matches HikariCP's message, because it has no SQLSTATE. A Hikari upgrade could change the text; the test pins it, and the result would be a 500 again, not a wrong answer.
- **Long statements.** `socketTimeout` 30 s would cut any app-role statement that legitimately runs longer; `statement_timeout` (10 s) already forbids those. Migrations are not affected: Flyway logs in as the owner through its own data source, which these Hikari properties don't reach, so a long index build in the migrate job is not cut.
- **Partial slowdowns.** The latency page ignores slowdowns that affect fewer than half the requests; they open a ticket instead.

## Consequences
- **Clients:** the API contract gains `service_unavailable` and `storage_unavailable`, both 503 with `Retry-After`. Clients should retry with backoff, reusing the same `Idempotency-Key` for checkout completion.
- **Logs:** dependency outages no longer flood the logs with ERROR stack traces.
- **Runbooks:** database-unavailable, object-storage, slo-burn.
- **New rule:** any new recorded ratio must divide by `(denominator > 0)`. A promtool test covers the idle case.
