# ADR-028: The HTTP edge: trusted proxies only, body limits, strict headers, CORS per API, storefront abuse limits

- **Status:** Accepted
- **Date:** 2026-10-09
- **Phase:** P14
- **Evidence:** [EXP-020](../experiments/EXP-020-forwarded-headers-and-oversized-bodies.md); `HttpEdgeIT`, `StorefrontRateLimitIT`

## Context
The P14 review probed the edge of the application before reading code. Two probes broke it: spoofed `X-Forwarded-For` defeated every per-IP limit, and JSON bodies of any size were parsed. The storefront, anonymous by design, had no abuse limits at all.

## Problem
1. Which client address and host can be believed?
2. How much may a request cost before anything is decided?
3. What may a browser do with our responses, and which origins may call which API?
4. How are anonymous, automatable flows (card testing, code guessing) contained?

## Options Considered
1. **Forwarded headers:** trust always (`framework`); never; **only from configured proxies (Tomcat RemoteIpValve)**.
2. **Body size:** rely on the load balancer; **a filter in the app (declared and counted)**.
3. **Storefront limits:** in-memory per instance; **the shared Redis limiter, failing open (ADR-020)**.

## Decision
1. `server.forward-headers-strategy: native`, `internal-proxies: ${TRUSTED_PROXIES:127.0.0.0/8, ::1/128}`. In production, the load balancer's range.
2. `RequestSizeLimitFilter`: 1 MB by default (`HTTP_MAX_REQUEST_BODY`); 413 `payload_too_large` from `Content-Length`, or while counting a chunked body.
3. **Headers:**
   - JSON APIs: CSP `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, plus `Referrer-Policy: no-referrer`, COOP and `Permissions-Policy`, on top of Spring Security's defaults;
   - OAuth pages: their own CSP, with the inline style allowed by its SHA-256 hash.

   **CORS:** `/auth` and `/admin` only from `AUTH_ALLOWED_ORIGINS`, with credentials; `/storefront` from any origin, never with credentials; `/api`, `/webhooks`, `/oauth` none. Rejections are Problem Details.
4. **Storefront limits per client address** (IPv6 per /64), shared through Redis (`DistributedRateLimiter`, extracted from the developer API's limiter):

   | Limit | Budget |
   |---|---|
   | general | 300/min |
   | cart creation | 20/min |
   | checkout completion | 10/min |
   | discount attempts | 10 per 10 min |
   | search | 60/min |

## Why We Chose It
- The address a limit counts must come from something the client can't choose. A proxy allow-list is the standard answer; trusting everyone was a default carried over unexamined from P1.
- Rejecting an oversized body costs microseconds; parsing it costs CPU and memory before any other check runs.
- A JSON API has no reason to render, frame or load anything: forbid it all.

## Trade-offs
- Behind an unlisted proxy, every client looks like the proxy (one shared budget) and X-Forwarded-Host is ignored: deployments must set `TRUSTED_PROXIES`.
- Per-address limits punish shared addresses (offices, carrier NAT) and are evaded by botnets: they bound automation, they don't stop it.
- Redis down: storefront limits become per-instance (N× looser), as for the developer API.

## Consequences
- Integration tests connect from loopback, which is trusted in tests, as a local proxy would be. Tests that must prove untrusted behaviour connect through the machine's LAN address.
- `storeforge_ratelimit_decisions_total` gained a `limit` tag (`developer-api` for P12's limiter).
