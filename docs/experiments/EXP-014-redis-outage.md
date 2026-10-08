# EXP-014: Redis stops under two instances

- **Date:** 2026-10-08 · **Phase:** P12
- **Setup:** two app instances (8080/8081, 8082/8083), Compose PostgreSQL, Mailpit, Redis (no persistence); one API key per step (`read_orders`).

## Experiment
1. 60 developer API requests alternating between the two instances.
2. `docker compose stop redis`, then 60 more with a fresh key. Read `/actuator/health` and readiness.
3. `docker compose start redis`, wait 13 s (the breaker waits 10 s), then 60 more with a fresh key.

## Expected behavior
- **Redis up:** one *shared* budget (40 + refill) across both instances.
- **Redis down:** still serving, with per-instance limits, no requests waiting on Redis, and health saying "impaired" without taking instances out.
- **Redis back:** the shared budget returns without a restart.

## Actual behavior

| Step | Allowed / 60 | Latency p50 / max | Notes |
|---|---|---|---|
| Up | **42** (21 + 21) | 15 / 635 ms | max = first request (lazy connect + script load) |
| Down | **60** | 14 / 54 ms | per-instance buckets (40 each > 30 each); breaker CLOSED → OPEN at once |
| Down: health (first run) | n/a | n/a | **`/actuator/health` HTTP 503 (DOWN)**; readiness UP |
| Down: health (after fix) | n/a | n/a | HTTP 200, `DEGRADED`, redis `DEGRADED`; readiness UP |
| Back | **41** (21 + 20) | 11 / 41 ms | breaker OPEN → HALF_OPEN → CLOSED by itself |

## What failed / why
The Redis health indicator reported **DOWN**. Readiness excluded Redis (good), but the aggregate `/actuator/health` turned DOWN → 503. Any monitor or load balancer pointed at `/actuator/health` would have declared healthy instances dead *during the very outage they were designed to survive*.

A stopped Redis closes its connections, so Lettuce (configured with `REJECT_COMMANDS`) failed instantly: no timeouts at all. The frozen-Redis case in `RateLimitIT` (paused container: connections stay open, nothing answers) is the slower failure mode, and the breaker handles it.

## How it was detected
The experiment's health check threw an HTTP 503 error in the driver script.

## How the system recovered
Requests were never affected. Health was wrong until the fix.

## What we changed
- A custom `DEGRADED` status, ordered between DOWN and UP and mapped to HTTP 200, used by the Redis indicator.
- Regression assertion in `RateLimitIT`: while Redis is frozen, `/actuator/health` is 200 + DEGRADED and readiness is 200.
- The fail-open / fail-closed table in [ADR-020](../decisions/ADR-020-redis-fail-open.md).
