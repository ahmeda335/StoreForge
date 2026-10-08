# Sending Webhooks: Signing, Retrying, Dead Letters, Replay, Rotation

**Problem.** Merchants want their systems to react to store events (sync stock to a warehouse, notify an ERP). We must deliver every event to an endpoint that is often slow or down, prove it came from us, and never let one bad endpoint hurt everyone else.

**Why we need it.** It's the integration surface of the platform (P11); apps build on it (P12).

**Alternatives.**
- **Polling the API only:** simple and reliable, but laggy and wasteful. It stays the recommended complement (reconciliation).
- **Streaming (SSE / WebSockets):** the merchant must keep a connection open.
- **A managed service (Svix, Hookdeck):** good. Here we build it to learn the mechanics.

**Decision.** Outbox event → idempotent fan-out → one delivery per subscription → signed POST → our own retry schedule → DEAD → replay; plus pause and secret rotation ([ADR-018](../decisions/ADR-018-outbound-webhooks.md)).

**Mental model.**
- **The receiver is the other half of the protocol.** Every guarantee we make needs something from them: verify the signature, dedup by event id, answer fast, don't assume order. Publish that checklist ([api/webhooks.md](../api/webhooks.md)).
- **Sign, don't encrypt.** HTTPS protects the transport; the HMAC proves *we* sent *these exact bytes* at *that time*. Timestamp + body are signed together, so neither can be swapped. A shared secret per subscription limits the blast radius of a leak.
- **The secret must be usable, so it can't be hashed.** It's encrypted at rest with a key outside the database, and shown once.
- **Rotation without downtime:** sign with old *and* new for an overlap window. Receivers accept any matching `v1`.
- **Freeze the payload.** Every retry and replay sends identical bytes with the same event id. A replay is "the same event again", not a new event.
- **Retry schedule ≠ queue backoff.** Receivers break for hours (deploys, weekends). Spread attempts over days (1m … 24h), with jitter, honouring `Retry-After`. 4xx usually means "your config is wrong": give up sooner.
- **DEAD is a state, not a disappearance.** It's visible in the history and replayable one by one or in bulk ("everything failed since 10:00").
- **Protect the platform from one endpoint:**
  - a hard deadline per attempt;
  - a connection cap per endpoint;
  - a bulkhead for delivery jobs ([bulkheads note](bulkheads-and-head-of-line-blocking.md));
  - pausing after N consecutive failures (and telling the merchant).
- **Thin payloads + fetch:** ids and routing values. Receivers fetch the current state, which also fixes ordering problems.

**Project usage.** `webhooks.internal`: `WebhookFanout`, `DeliveryService`, `RetryPolicy`, `WebhookSender`, `SecretBox`, `WebhookSignature`, `Topics`, `SubscriptionService`, `AdminWebhooksController`.

**Configuration.** `storeforge.webhooks.*`: schedule, max attempts (9, and 3 for 4xx), pause threshold (20), connections per endpoint (4), timeouts (2 s connect / 10 s total), secret overlap (24 h), retention (30 d), encryption key, https and SSRF settings.

**Failure cases.**
- Re-serializing the payload per attempt (the bytes change);
- new event ids on replay (receivers can't dedup);
- signing without a timestamp (replayable);
- retrying forever, or silently dropping;
- following redirects;
- one slow endpoint starving all background work ([EXP-012](../experiments/EXP-012-head-of-line-blocking.md));
- a test that "proves" a deadline but is actually satisfied by a different timeout ([EXP-013](../experiments/EXP-013-webhook-mutations.md)).

**Testing.**
- A WireMock receiver that verifies signatures with independent code;
- a raw-socket receiver that drips bytes;
- time travel (`runDueAt(now + 25m)`) for retry schedules;
- mutation checks of each defense.

**Observability.**
- `storeforge_webhooks_deliveries_total{outcome=succeeded|retry|dead|blocked}`;
- `storeforge_webhooks_delivery_duration_seconds{outcome}`;
- `storeforge_webhooks_subscriptions_paused_total`;
- the delivery history API; WARN logs with `deliveryId` and `host` (never the full URL, which may carry tokens).

**When NOT to use it.** Internal service-to-service messaging (use a queue). Data that must arrive in order: use a stream, or design receivers to fetch state.
