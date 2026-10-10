# EXP-024: Production-like delivery: deploys under traffic, broken releases, restore, alerts

- **Date:** 2026-10-10 · **Phase:** P16 · **Setup:** the `deploy/` stack (staging, then production) on one laptop; k6 at 4 requests/s through Caddy to a real store's storefront during deploys ([load/k6/during-deploy.js](../../load/k6/during-deploy.js)).

## Experiment
1. Bring up staging from nothing (secrets, images, migrate, roll out, smoke test).
2. Deploy a new image under steady traffic.
3. Deploy two broken images under traffic: one fails at startup everywhere; one migrates but never becomes healthy.
4. Base backup, then new data (only in WAL), then a restore drill.
5. Stop an API instance; wait for the page and the resolved notice.
6. Promote the same image to production (separate secrets).

## Expected behavior
No user-visible errors during deploys and failed deploys; broken releases never serve; the restore matches the live data; the outage pages within a few minutes.

## Actual behavior

| Step | Result |
|---|---|
| Staging from nothing | 22 migrations, 3 app instances healthy, smoke OK in 2.2 s |
| Deploy under traffic | **0 errors / 601 requests**, p95 35 ms, max 1.5 s at a handover; 71 s total |
| Broken (invalid setting) | Stopped at the migrate job: nothing rolled out; 0 errors / 800 |
| Broken (never healthy) | `api-1` unhealthy after 71 s → rolled back automatically; **0 errors / 1,200** |
| Restore drill | Latest base backup + WAL (including data written after the backup): identical counts, **RTO 10 s** (small database) |
| Instance down | `InstanceDown` email to on-call after **2 min 17 s**; RESOLVED after recovery (+ up to 5 min, Alertmanager's group interval) |
| Production | From nothing in 1 min 44 s; smoke OK; secrets differ from staging |

Findings on the way:

| Finding | Kind |
|---|---|
| **Login rate limits were per instance:** behind 2 instances the first 429 came at attempt 63, not 31 | Product bug (P2 design, exposed by scaling out) |
| First deploy under traffic: 3,453 "errors" and a rollback | Test design (one client address at 30/s hit the storefront limit, and so did the smoke test) |
| Caddy and X-Forwarded-For | Verified, not a bug: Caddy replaces a client's header with the real address |
| `${ENVIRONMENT}` inside YAML flow mappings broke the Compose file | Tooling |
| Fixed proxy IP taken by another container | Tooling (dynamic range now excludes it) |
| A migrate profile without a web server failed (security beans need one) | Bug in the new release job (fixed before commit) |
| `SERVER_PORT` in the environment overrides the migrate profile's `server.port` (relaxed binding) | Learning (shaped the broken-image tests) |

## What failed / why
- **Per-instance login limits:** P2 kept login limits in memory ("per instance is acceptable on one instance"), and scaling to two instances silently doubled the budget. The first spoofing test looked like an `X-Forwarded-For` bypass; a header-echo experiment showed Caddy was correct, and a 70-attempt run after resetting both instances showed the 2× pattern.
- **The 3,453 errors** were the storefront's per-address limit doing its job. One machine is one address behind a proxy, so the test rate must stay under 300 per minute.

## How it was detected
k6 counters during deploys, the deploy script's step log, a throwaway Caddy in front of a header-echo server, Prometheus/Alertmanager state, Mailpit.

## How the system recovered
Automatic rollbacks; no manual step was needed in any failure case.

## What we changed
- Login limits on the shared Redis limiter (email keys hashed); the in-memory limiter removed.
- `during-deploy.js` rate and a separate 429 counter.
- Compose file and IP-range fixes.
- [ADR-031](../decisions/ADR-031-single-host-production-and-delivery.md), [deploy runbook](../operations/runbooks/deploy.md).
