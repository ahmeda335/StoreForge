# Trusted Proxies and Client Addresses

**Problem.** Behind a load balancer, the TCP peer is the balancer, so the real client is in `X-Forwarded-For`. But any client can send that header. If the app believes it from everyone, every per-IP control (rate limits, lockouts, audit IPs) uses an address the attacker chose.

**Why we need it.** EXP-020: rotating `X-Forwarded-For` gave a fresh login budget per request (40/40 attempts accepted).

**Alternatives.**
- **Trust the header always:** what `forward-headers-strategy: framework` did.
- **Never trust it:** everyone looks like the proxy (one shared budget).
- **Trust it only when the direct peer is a known proxy:** the standard answer.
- **PROXY protocol / signed headers:** for complex edges.

**Decision.** Tomcat RemoteIpValve (`native`) with `internal-proxies` = `TRUSTED_PROXIES` ([ADR-028](../decisions/ADR-028-http-edge-hardening.md)).

**Mental model.**
- `X-Forwarded-For: client, proxy1, proxy2` is appended to hop by hop. Read it **right to left**, skipping trusted proxies; the first untrusted address is the client. Everything to its left is attacker-controlled.
- The same applies to `X-Forwarded-Proto` (HTTPS detection, HSTS, secure cookies) and `X-Forwarded-Host` (which store a storefront request is for).
- **IPv6:** one host controls a whole /64, so limits key on the /64.
- A limit per address bounds automation from one place; botnets and shared addresses (carrier NAT) are its limits.

**Project usage.** `server.tomcat.remoteip.internal-proxies`; `request.getRemoteAddr()` everywhere; `StorefrontRateLimitFilter.clientKey`.

**Failure cases.**
- Trusting everyone;
- forgetting to list the real proxy (every client shares one budget);
- trusting a range clients can reach directly (e.g. all private ranges on a flat network).

**Testing.** Tests connect from loopback (trusted, like a local proxy) to set `X-Forwarded-For`, and through the machine's LAN address to prove the header is ignored from untrusted peers (`HttpEdgeIT`, `StorefrontRateLimitIT`).

**Observability.** Audit entries and logs with client IPs; rate-limit decisions by limit. If every request shows the balancer's address, `TRUSTED_PROXIES` is wrong.

**When NOT to use it.** Apps exposed directly to the internet: there's no proxy to trust, so ignore the headers.
