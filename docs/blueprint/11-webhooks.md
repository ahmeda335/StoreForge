# 11 — Outbound Webhooks

Webhooks turn internal events into notifications for merchants' and apps' external systems. They are a **reliability and security** feature: we send data to URLs we do not control.

## Subscription

`POST /admin/v1/stores/{id}/webhook-subscriptions { url, topics: ["orders/create", "orders/paid", "inventory_levels/update"], apiVersion }`.
- URL must be `https`, and DNS must resolve to **public** IPs only (SSRF guard). Checked at creation **and at every delivery**, because DNS can change (DNS rebinding).
- A secret is generated server-side (32 random bytes), shown once, and stored encrypted (AES-GCM with a key from the secret store) because we need the plaintext to sign. Hashing is not possible here.
- Max N subscriptions per store (quota).

## Fan-out

On an outbox event, the webhooks handler finds active subscriptions for `(store, topic)` and inserts one `webhook_deliveries` row per subscription with `UNIQUE(subscription_id, event_id)`, which makes fan-out idempotent. It enqueues a job per delivery.

## Delivery request

```http
POST {url}
Content-Type: application/json
User-Agent: StoreForge-Webhooks/1.0
X-StoreForge-Topic: orders/create
X-StoreForge-Event-Id: 0192f6b1-...        ← receiver dedup key
X-StoreForge-Delivery-Id: 0192f6b2-...
X-StoreForge-Store-Id: 0192f0aa-...
X-StoreForge-Api-Version: 2026-10
X-StoreForge-Timestamp: 1791234567
X-StoreForge-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>
```
During secret rotation, two signatures are sent: `v1=<new>,v1=<old>`.

- **Timeouts:** connect 2s, total 10s. No redirects followed (a redirect to `http://169.254.169.254` is the classic SSRF).
- **Success:** any 2xx. **Retryable:** timeouts, 408, 429 (honour `Retry-After`), 5xx. **Permanent:** other 4xx (still retried a few times, since merchants misconfigure temporarily, but faster to DEAD).
- **Backoff:** 1m, 5m, 30m, 2h, 6h, 12h, 24h… up to 72h total, with jitter.
- **Dead letter:** `DEAD` status, visible in delivery history, with a **replay** button/API.
- **Circuit breaking per endpoint:** after N consecutive failures across deliveries, pause the subscription (`status = PAUSED_FAILING`) and email the merchant. One dead endpoint must not consume all dispatcher capacity (bulkhead: per-store concurrency cap).
- **Delivery history:** attempts with status code, latency, truncated response body (no request headers stored that contain secrets). Retained 30 days.

## Replay

Replay creates a **new delivery attempt with the same event id** (so receivers dedup it correctly if they already processed it). Bulk replay is supported by time range ("replay all failed deliveries since 10:00").

## Secret rotation

`POST .../webhook-subscriptions/{id}/secret-rotation` → new secret returned; old secret kept for 24h; deliveries are signed with both during the overlap; after expiry only the new one is used. The receiver updates its config without downtime.

## How a merchant's system safely receives `order.created`, `payment.completed` and `inventory.updated`

Receiver checklist (published in developer docs):
1. Read the **raw body**; recompute HMAC with the secret over `timestamp.body`; compare in **constant time** against any `v1=` signature.
2. Reject if `|now - timestamp| > 5 min` (replay protection).
3. Deduplicate by `X-StoreForge-Event-Id` (store processed ids, e.g. 7 days).
4. Respond **2xx immediately** and process asynchronously (we time out at 10s).
5. Don't assume order: `orders/paid` can arrive before `orders/create`. Use `updatedAt`/version, or fetch the latest state from the API ("thin event + fetch" pattern).
6. Treat webhooks as hints and reconcile periodically via the API (`GET /orders?updatedAfter=`), because webhooks can be missed if the endpoint was down beyond the retry window.

Topic naming: public topics use `resource/action` (`orders/create`, `orders/paid`, `inventory_levels/update`). The prompt's `order.created`/`payment.completed`/`inventory.updated` map to these. Internal event names stay decoupled from public topics.
