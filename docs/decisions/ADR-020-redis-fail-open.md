# ADR-020: Redis for shared, losable state: rate limits and usage; fail open, report DEGRADED

- **Status:** Accepted
- **Date:** 2026-10-08
- **Phase:** P12
- **Evidence:** [EXP-014](../experiments/EXP-014-redis-outage.md)

## Context
The developer API needs rate limits that hold across instances: an in-memory limiter gives N× the budget with N instances. It also needs per-credential usage counters that are written on every request. PostgreSQL could hold both, but at one write per API request it would turn the primary database into a counter store.

## Problem
1. Where does shared, high-frequency, short-lived state live?
2. What happens to the API when that store is down?
3. How is that outage reported, without taking healthy instances out of service?

## Options Considered
1. **Store:** PostgreSQL counters; sticky routing plus in-memory limits; **Redis**.
2. **When Redis is down:**
   - **fail closed** (refuse requests);
   - **fail open with no limits**;
   - **fail open to per-instance limits**.
3. **Health:** Redis in readiness; Redis DOWN in `/actuator/health`; **a custom DEGRADED status**.

## Decision
1. **Redis**, accessed with plain Lettuce (no Spring Data):
   - lazy connection, so the app starts without Redis;
   - 250 ms command timeout;
   - commands **rejected immediately while disconnected**, not queued until Redis returns (Lettuce's default turns an outage into hanging requests);
   - Bucket4j token buckets (Lua CAS): per credential 40 burst / 2 per s, and per store 100 / 5 per s;
   - usage hashes per credential and hour, rolled up every 5 minutes into `app_usage_hourly` as absolute values (idempotent).
2. **Developer API: fail open to per-instance in-memory buckets** with the same limits. A Resilience4j circuit breaker skips Redis entirely while it's failing (no per-request timeouts), and closes again by itself. Usage increments are dropped and counted (`storeforge_usage_dropped_total`).

   **What lives where, per API:**

   | API | Limits | Without Redis |
   |---|---|---|
   | Developer API | per credential + per store (Redis) | per-instance limits (fail open) |
   | Auth endpoints | per IP / per email (in-memory, P2) | unaffected |
   | Storefront | none yet (P13) | n/a |
   | OAuth authorize/token | auth limits via `PasswordAuthenticator` | unaffected |

   **Fail closed** would be right for a limit that protects money or a hard quota (e.g. a paid plan's monthly cap). None exists yet.
3. Redis down is **DEGRADED** (HTTP 200, ordered between DOWN and UP) in `/actuator/health`, and it isn't in readiness.

## Why We Chose It
- **Fail open:** an outage of a *cache* must not become an outage of every merchant integration. Per-instance limits keep abuse bounded (at most N× the budget) for the duration.
- **No waiting:** the breaker means requests don't pay for the outage. Measured: p50 14 ms with Redis stopped.
- **Health:** EXP-014 showed that reporting DOWN made `/actuator/health` answer 503 during an outage the app was surviving.

## Trade-offs
- **Looser limits during an outage:** N× the budget. Acceptable for a soft limit.
- **Lost usage statistics** for the duration. Acceptable for analytics; not acceptable for billing (which would need durable counting).
- **One more moving part to operate.** Redis runs without persistence: nothing in it is irreplaceable.

## Consequences
- **New Redis uses** must state their failure mode (open / closed) and stay inside "shared and losable".
- **Session data for the OAuth browser flow is not in Redis yet.** It's in-memory per instance: multi-instance installs need sticky sessions or Spring Session Redis (open item).
