# Server-Side Request Forgery (SSRF)

**Problem.** A webhook URL is chosen by a merchant (or by whoever stole a merchant's session). Our server then makes an HTTP request to it, *from inside our network*. Pointed at `http://169.254.169.254/latest/meta-data/iam/…`, that request reads the cloud credentials of our servers. Pointed at `http://10.0.3.7:5432` or an internal admin port, it probes our infrastructure. SSRF is in the OWASP API Top 10 (API7:2023) for this reason.

**Why we need it.** Any feature where *we* fetch a user-supplied URL: webhooks (P11); later, image imports and app callbacks.

**Alternatives.**
- **A blocklist on the URL string** (`localhost`, `127.`): trivially bypassed with `2130706433`, `[::ffff:127.0.0.1]`, `0x7f.1`, hostnames that resolve to private IPs, or DNS that changes later.
- **An egress proxy** that enforces the policy for all outbound traffic: strong. It's the production-grade complement, at the network layer.
- **An allow-list of destinations:** impossible for webhooks (any merchant domain).

**Decision.** Decide on **resolved addresses** inside the HTTP client's **DNS resolver**, never follow redirects, and require https ([ADR-018](../decisions/ADR-018-outbound-webhooks.md)).

**Mental model.**
- **The question isn't "what does the URL say", but "where will the packets go".** Resolve the host, and refuse if **any** address is non-public: loopback, RFC 1918, link-local (metadata), CGNAT, multicast, reserved, IPv6 ULA and link-local, and IPv6 forms that embed an IPv4 address.
- **Time-of-check vs. time-of-use (DNS rebinding).** Validating at subscription time, then letting the client resolve again at send time, leaves a window. A malicious DNS server answers "public" for the check and "169.254.169.254" for the request. Our check *is* the client's resolver, so the addresses checked are the addresses connected to.
- **Redirects are a second URL** the user chose without us validating it. Don't follow them.
- **Limit what a successful request can do:**
  - total deadline (10 s);
  - small response excerpt;
  - no secrets in the request (only the HMAC);
  - response bodies are never interpreted.
- **Errors shouldn't become an oracle.** We tell the merchant "resolves to a non-public address", not which internal service answered.

**Project usage.** `EndpointPolicy` (rules, `Cidr`), `WebhookSender` (resolver hook, `disableRedirectHandling`, deadline), `SubscriptionService.validateUrl` (early feedback).

**Configuration.**
- `storeforge.webhooks.require-https` (true);
- `allowed-private-cidrs`: an explicit escape hatch for local receivers and tests (`127.0.0.0/8`). The app logs a WARN when it's used.

**Failure cases.**
- String blocklists;
- checking only the first resolved address;
- following redirects;
- forgetting IPv6 forms;
- allowing `http` in production;
- leaking internal responses in error messages;
- an escape hatch left on in production.

**Testing.**
- `EndpointPolicyTest`: 29 cases with a fake DNS resolver, including rebinding.
- `WebhookDeliveryIT`: a 302 to the metadata IP is recorded, not followed; a stored URL changed to `169.254.169.254` after validation is never contacted.
- The manual run shows API refusals for each encoding.

**Observability.** `storeforge_webhooks_deliveries_total{outcome="blocked"}`, the WARN "Webhook delivery attempt failed: destination not allowed" with `deliveryId` and `host`, and the audit log entry for the subscription.

**When NOT to use it.** When the destinations are fixed, known services (Stripe API): just configure them.
