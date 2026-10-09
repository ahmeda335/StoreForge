# EXP-020: Probing the edge: spoofed client addresses and oversized bodies

- **Date:** 2026-10-09 · **Phase:** P14 · **Setup:** the app from the jar, Compose PostgreSQL/Redis/Mailpit; curl from the same machine (loopback and the LAN address 192.168.1.3).

## Experiment
1. 40 failed logins (`POST /auth/v1/token`, wrong password, different emails) from one address: once with a constant `X-Forwarded-For`, once with a different one per request.
2. A 20 MB JSON body to `/auth/v1/token`.
3. After the fixes: the same from the LAN address (not a trusted proxy), and the 20 MB body again.

## Expected behavior
The per-IP limit (30 per window) stops both runs after 30 attempts. A 20 MB body is refused without being parsed.

## Actual behavior

| Probe | Before | After |
|---|---|---|
| 40 logins, constant XFF | 30 × 401, 10 × 429 | same |
| 40 logins, rotating XFF | **40 × 401, 0 × 429** | 30 × 401, 10 × 429 (from the LAN address) |
| 20 MB JSON body | **422 after 425 ms** (read and parsed, then validation failed) | 413 `payload_too_large` in 3 ms |

## What failed / why
- `server.forward-headers-strategy: framework` (P1) makes Spring believe `X-Forwarded-*` from **any** client. Every per-IP limit (auth, and the storefront limits being added) keyed on an address the attacker chose. Per-email limits and lockout still protected single accounts; credential stuffing across many accounts was unlimited.
- Tomcat's `maxPostSize` applies only to form posts. JSON bodies had no limit: a few parallel 20 MB requests tie up CPU and heap before any authentication or validation runs.

## How it was detected
Black-box probes before reading the code: the review started from "what can a client send?".

## How the system recovered
N/A.

## What we changed
- Tomcat RemoteIpValve: forwarded headers only from `TRUSTED_PROXIES` (default loopback).
- `RequestSizeLimitFilter` (1 MB; declared and chunked).
- Regression tests that connect through the LAN address (`HttpEdgeIT`, `StorefrontRateLimitIT`); mutation: putting back `framework` fails both.
- [ADR-028](../decisions/ADR-028-http-edge-hardening.md).
- **Lesson:** an address-based control is only as good as the source of the address.
