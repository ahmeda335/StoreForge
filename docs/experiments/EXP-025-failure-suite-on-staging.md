# EXP-025: Failure suite on staging: database, Redis, latency, locks, worker, storage, noisy client

- **Date:** 2026-10-10 · **Phase:** P17 · **Setup:**
  - the P16 staging stack ([deploy/](../../deploy/README.md)) with the chaos overlay ([deploy/chaos/](../../deploy/chaos/README.md)): the app reaches PostgreSQL and Redis through Toxiproxy 2.12.0;
  - images `fc1314e` + P17 changes (`-p17a` … `-p17d`); Hikari pool 10 per instance, `connection-timeout` 3 s, `statement_timeout` 10 s;
  - **steady state:** k6 2.3.0 through Caddy ([traffic.js](../../deploy/chaos/traffic.js)): browse 3/s plus 6 complete checkouts/min against one store with deep stock. That is under every per-address limit, so any failure is the fault's.

## Experiment
Each run ([run.sh](../../deploy/chaos/run.sh)) follows the same shape:
- 60 s of steady traffic;
- the fault, held for 30–120 s;
- heal, then 180 s of steady traffic.

Each run then reports:
- failures by kind (connection, 503, 5xx, 4xx) and step (browse, cart, add, checkout, complete), before, during and after the fault;
- the time from heal to the last failure;
- active alerts;
- the **data invariants** ([invariants.sql](../../deploy/chaos/invariants.sql)): orders have their PLACED event and OrderPlaced outbox row; completed checkouts have orders; totals add up; inventory and reservations agree; no job past its lease; no outbox event unpublished for > 5 min.

Abort: Ctrl-C or SIGTERM heals first, then stops the traffic.

| # | Fault | Catalogue |
|---|---|---|
| E1 | `docker compose kill postgres`, 90 s | #1 |
| E2 | `docker compose kill redis`, 120 s (and 30 s on the final build) | #2 |
| E3 | Toxiproxy: +200 ms ± 50 ms on every PostgreSQL response, 120 s | #9 (slow, not down) |
| E4 | `LOCK store_counters IN EXCLUSIVE MODE` for 60 s (every order number blocked) | #9 (long-held lock) |
| E5 | `SIGKILL` to the worker, 60 s down | #8 |
| E6 | One client at 200 requests/s to one store's search, 120 s, from a second address | #7 |
| E7 | `docker compose stop seaweedfs`, then a full image upload | (storage) |

The other catalogue entries are already covered elsewhere:

| Catalogue | Covered by |
|---|---|
| #3 slow payment provider | P10 tests: the Stripe adapter's read timeout gives an unknown outcome (`StripePaymentProviderContractTest`); a lost response becomes a 503 retried with the same key (`CardPaymentIT`); the breaker pauses calls (`PaymentRecoveryIT`). Staging uses the in-process fake, so there is no network for Toxiproxy to slow. |
| #4, #5 duplicate webhooks and callbacks | P10/P11 tests, [EXP-010](EXP-010-payment-mutations.md), [EXP-011](EXP-011-lost-webhooks.md), [EXP-013](EXP-013-webhook-mutations.md) |
| #6 last unit | [EXP-004](EXP-004-oversell-three-ways.md) |
| #10 broker | not applicable (no broker) |
| #11 expiry | P5 tests |
| #12 deploy under traffic | [EXP-024](EXP-024-production-like-delivery.md) |

## Expected behavior
- **E1:** fast 503s while the database is down, never 500s or hangs; recovery within seconds of PostgreSQL accepting connections; the availability page fires; no half-written orders.
- **E2:** nothing fails; rate limits fall back to per instance; the outage is logged a few times, not per request.
- **E3:** slower, but no errors; no pool starvation; a latency page if it lasts.
- **E4:** only checkout completion is affected (each is cut by `statement_timeout`); browsing is unaffected.
- **E5:** no user-visible effect; jobs resume after restart; nothing lost.
- **E6:** the flooding client gets 429; everyone else is unaffected.
- **E7:** only uploads fail, with a "retry later" answer; shopping is unaffected.

**Invariants hold everywhere.**

## Actual behavior

| # | Before fixes | After fixes |
|---|---|---|
| E1 | **407 × 500**; each failure took 3 s (pool timeout). The last failure came **+44 s after heal**, although PostgreSQL accepted connections at +3 s: the pool stayed at 0 connections for ~45 s. Page by email **66 s** after the kill. | **503 + `Retry-After`**. Three runs: the last failure came +6 s, +5 s and +20 s after heal, which is **2–3 s after PostgreSQL was ready** in each case (it needed 3 s, 3 s and 17 s to finish crash recovery). Runs 2 and 3 still had 1 × 500 each, from a rollback on an evicted connection, fixed for run 4: **0 × 500**. Run 5, after the alert fix (below) and with a clean 1 h window: 0 × 500, recovery +6 s, **paged after 59 s**. |
| E2 | 0 failures, 0 requests over 1 s. Limiter decisions moved to `backend=local` during the outage (storefront 475 local vs 742 Redis). | Same on the final build (30 s): 0 failures. Logs per instance: 7 breaker state changes and 3 reconnect messages, **not per request**. |
| E3 | 0 failures; 387 of 402 requests over 1 s (several round trips each); no shedding, no pending connections. **No page:** the latency fast burn needs the 1 h ratio above 72 %, which a total slowdown reaches only after ~43 min. | The latency page now fires on > 50 % slow over 15 min and 5 min (~8 min for a total slowdown); promtool test. A 2-minute blip still doesn't page, by design. |
| E4 | 4 checkout completions **500** (`QueryTimeoutException` after 10 s); browsing: 0 failures, no slow requests, **no pool starvation** (one blocked completion at a time holds one connection). | 4 × **503 + `Retry-After`**. The idempotency record rolled back with the transaction, so a retry is safe. |
| E5 | 0 failures; after restart, all 438 recent jobs were DONE with max 1 attempt; 76/76 order emails sent; 3 outbox rows briefly unpublished (relay interval), none older than 5 min. | (no change needed) |
| E6 | Steady traffic: **0 failures, 0 slow**. The flood: 22,536 storefront and 564 search rate-limit rejections (429). | (no change needed; see limitations) |
| E7 | Upload start 201; PUT through the proxy 502; **completion 500**. Storefront 200, cart 201. | Completion **503 `storage_unavailable`, `Retry-After: 30`**, in 0.2 s. After restart the S3 API took ~10 s to answer, then uploads succeeded. WAL archiving resumed with no failed archives (the outage was shorter than a segment). |

**Invariants:** 0 violations after every completed run (13).

**Found while analysing the runs: the pages were blind for an hour after idle time.** The second and third E1 runs (after the fixes) sent **no page**, although 50 % of requests failed for 2 minutes:
- `storeforge:error_ratio:rate1h` was `NaN`;
- the request rate had been 0 for 3 minutes during a redeploy, so the 5-minute ratio was 0/0 = NaN;
- one NaN sample makes `avg_over_time` NaN for its whole window, and `NaN > x` is false;
- so the page was disabled for 1 hour and the ticket for 6 hours, and the same would happen after every quiet night.

After the fix, run 5 paged 59 s after the kill.

## What failed / why
1. **Dependency outages were 500 "unexpected error":**
   - `CannotGetJdbcConnectionException`, `QueryTimeoutException` and S3 client errors reached the catch-all handler;
   - clients and proxies treat 500 as "don't retry";
   - on-call saw ERROR stack traces that look like bugs.
2. **Slow pool recovery (E1):** the PostgreSQL driver had no connect, login or socket timeouts, and Hikari creates connections on one thread. Adding the timeouts reduced recovery from ~41 s to 2–3 s after the database was ready, in all three runs. The mechanism was not traced (a likely candidate: a connection attempt hanging on a half-open path through the proxy); the before/after numbers are what the fix rests on.
3. **Lost-connection rollback (E1, after fix 1):**
   - Hikari closes a connection that failed with SQLSTATE 08;
   - Spring's rollback then hits the closed proxy, and Hikari throws `SQLException("Connection is closed")` with no SQLSTATE;
   - `TransactionTemplate` throws that exception instead of the original one (which it only logs).
4. **Alert rules:**
   - the latency recipe copied from availability can't page a total slowdown in time (the budget is 5 %, so the maximum burn is 20×);
   - the 0/0 ratio poisoned the averages after an idle stretch.

## How it was detected
- The runner's failure breakdown by kind and step, plus the timing relative to inject and heal.
- Exception types from the JSON logs.
- Prometheus queries: `hikaricp_connections`, `hikaricp_connections_pending`, the recorded ratios.
- `pg_stat_archiver`; Mailpit and Alertmanager for pages.
- **The blind alert was found by asking why a page was missing**, not by an alert.

## How the system recovered
Automatically in every case. No restarts, no manual steps, no data repair.

## What we changed
- [`ApiExceptionHandler`](../../src/main/java/dev/storeforge/shared/web/ApiExceptionHandler.java):
  - no connection, no transaction, statement or lock timeout, deadlock, and lost connection (SQLSTATE 08 or Hikari's closed proxy, however wrapped) all answer **503 `service_unavailable` + `Retry-After: 5`**;
  - they are logged at WARN; bugs remain 500/ERROR.
- [`StorageUnavailableException`](../../src/main/java/dev/storeforge/storage/StorageUnavailableException.java): S3 network errors and 5xx answer 503 `storage_unavailable` + `Retry-After: 30`. A 403 stays a 500, because it's our misconfiguration.
- PostgreSQL driver timeouts in `application.yml`: `connectTimeout` 3 s, `loginTimeout` 5 s, `socketTimeout` 30 s (above `statement_timeout`), TCP keepalive.
- [SLO rules](../../infra/prometheus/slo-rules.yml):
  - ratios are not recorded without requests;
  - the latency page is "> 50 % slow over 15 min and 5 min";
  - two new promtool tests.
- Tests: `ApiExceptionHandlerTest` (MockMvc through the real handler; 8 cases) and `S3ObjectStorageFailureTest` (unreachable, 502, 403).
- Mutations (all caught):
  - SQLSTATE class;
  - cause walk;
  - fallback not consulted;
  - Hikari case;
  - statement timeout unmapped;
  - S3 5xx unmapped, network unmapped, 4xx mapped;
  - the NaN rule (the new promtool test fails without the fix).
- Chaos tooling: [deploy/chaos/](../../deploy/chaos/README.md); [ADR-032](../decisions/ADR-032-failure-semantics.md); runbooks updated.
