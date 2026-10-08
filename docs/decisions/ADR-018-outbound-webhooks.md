# ADR-018: Outbound webhooks: own retry schedule, SSRF guard at connect time, encrypted secrets, frozen payloads

- **Status:** Accepted
- **Date:** 2026-10-08
- **Phase:** P11
- **Builds on:** [ADR-016](ADR-016-async-infrastructure.md) (outbox, job queue); companion to [ADR-019](ADR-019-concurrent-jobs-and-bulkheads.md)

## Context
Merchants subscribe URLs to store events. We then send data, from inside our network, to URLs that someone else chose. Receivers are slow, down, misconfigured or hostile. Events must not be lost, must not arrive forged, and must not be usable to reach our internal network.

## Problem
1. How do retries work: the job queue's generic backoff, or something specific?
2. How do we stop SSRF when the URL is attacker-controlled and DNS can change after validation?
3. How do we store a secret we must use in plaintext (to sign)?
4. What exactly is sent on a retry or replay, and how do receivers deduplicate?
5. What happens to an endpoint that is down for days?

## Options Considered
1. **Retries:**
   - (a) the job queue retries the delivery job (5 s → 1 h, 10 attempts, ~2.5 h);
   - (b) **the delivery decides its own next attempt and enqueues a delayed job for it**.
2. **SSRF:**
   - (a) validate the URL at subscription time;
   - (b) also re-resolve and check before each request;
   - (c) **make the check the HTTP client's DNS resolver**, so the addresses checked are the addresses connected to.
3. **Secrets:** hash them (impossible: we sign with them); plaintext; **AES-GCM with a key outside the database**.
4. **Payload:** rebuild it on each attempt; **freeze the exact body at fan-out** (`text`, not `jsonb`, which reorders keys).
5. **Dead endpoints:** retry forever; disable silently; **pause after N consecutive failures, email the merchant, keep new events for replay**.

## Decision
1. **(b).** `RetryPolicy`:
   - schedule 1m, 5m, 30m, 2h, 6h, 12h, 24h, 24h: 9 attempts over ~70 h, ±10% jitter;
   - `Retry-After` (429/503) means "not before", capped at 24 h;
   - 4xx other than 408/429 stops after 3 attempts;
   - after the last attempt the delivery is DEAD.

   Each attempt is its own delayed job (P10's `runAt`). The queue's own retry now covers only *our* failures (e.g. the database down while recording an attempt).
2. **(c)**, plus validation at creation for early feedback (`422 webhook_url_not_allowed`).
   - The policy refuses non-public addresses: RFC 1918, loopback, link-local/metadata, CGNAT, multicast, ULA, documentation ranges, and IPv6 forms embedding IPv4 (mapped, NAT64, 6to4).
   - **All** resolved addresses must pass, not just one.
   - The URL must be https and carry no credentials.
   - Redirects are never followed.
   - Local development and tests opt in to `127.0.0.0/8` explicitly (`allowed-private-cidrs`).
3. **AES-256-GCM** (`SecretBox`):
   - the key comes from `WEBHOOK_ENCRYPTION_KEY`; the dev key in `application.yml` logs a WARN;
   - the subscription id is bound as associated data, so a ciphertext copied to another row fails to decrypt;
   - the secret is shown once, at creation and at rotation.
4. **Frozen body.** `X-StoreForge-Event-Id` (the outbox event id) is the receivers' dedup key, identical across attempts and replays. A replay is the same delivery in a new cycle (`replays + 1`).
   - Signature: `v1=HMAC-SHA256(secret, timestamp + "." + body)`; two `v1` values during a 24 h rotation overlap.
5. **Pause** (`PAUSED_FAILING`) after 20 consecutive failed attempts across deliveries:
   - an email goes to the subscriber;
   - deliveries created or due while paused are parked as `PAUSED`;
   - re-activating doesn't send them automatically: the merchant bulk-replays after fixing the receiver.
6. **Resource limits per attempt:**
   - connect 2 s, plus a **hard total deadline** of 10 s (the request is cancelled even if bytes keep trickling in);
   - at most 4 connections per endpoint per instance;
   - 512 bytes of the response are kept.

## Why We Chose It
- **Retry schedule:** webhook outages last hours or a weekend. The generic job schedule would give up after ~2.5 h and retry too often early on.
- **SSRF:** checking *before* the request and then connecting separately leaves a DNS-rebinding window. A resolver-level check closes it by construction. Proven by test: a URL changed to the metadata IP after validation is never contacted.
- **Secrets:** a database dump or backup must not let anyone forge our webhooks.
- **Pause and replay:** nothing is lost, failing endpoints stop costing capacity, and the merchant is told.

## Trade-offs
- **No ordering:** deliveries run concurrently and retries reorder them; we observed `orders/paid` arriving before `orders/create`. Receivers must not assume order (documented in the receiver guide).
- **At-least-once:** a worker crash after sending, before recording, re-sends. Receivers dedup by event id.
- **Encryption-key rotation** isn't implemented yet (the format carries a `v1:` version prefix for it).
- **The pause threshold counts attempts, not time:** a busy store pauses sooner than a quiet one.

## Consequences
- **New topic:** add it to `Topics` (mapping and payload); the payload is a public contract per `apiVersion`.
- **New outbound HTTP to merchant-chosen URLs** must reuse `WebhookSender` and `EndpointPolicy`.
- **Runbook:** [webhooks](../operations/runbooks/webhooks.md).
