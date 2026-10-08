# Webhooks API and receiver guide (implemented, P11)

Design: [domains/webhooks.md](../domains/webhooks.md). Topics and payloads: [domains/webhooks.md#topics-api-version-2026-10](../domains/webhooks.md#topics-api-version-2026-10).

## Admin API: `/admin/v1/stores/{storeId}/webhook-subscriptions` (`webhooks:manage`)

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /` | `{url, topics[], apiVersion?}` | `201` subscription **with `secret` (shown once)** | `webhook_url_not_allowed` (422: not https, credentials in URL, or resolves to a non-public address), `unknown_topic`, `unsupported_api_version` (422), `webhook_subscription_limit` (409, 20 per store) |
| `GET /` | — | `200 {data: [...]}` (never the secret) | |
| `GET /{id}` | — | `200 {id, url, topics, apiVersion, status, consecutiveFailures, lastSuccessAt, lastFailureAt, pausedAt, previousSecretExpiresAt}` | `webhook_subscription_not_found` |
| `PATCH /{id}` | `{url?, topics?, status?: ACTIVE\|DISABLED}` | `200`; `ACTIVE` re-activates a paused subscription | as above |
| `DELETE /{id}` | — | `204` (history deleted too) | |
| `POST /{id}/secret-rotation` | — | `200` with the new `secret`; the old one keeps signing for 24 h | |
| `GET /{id}/deliveries?status=&limit=&cursor=` | — | `200` page of `{id, eventId, topic, status, attempts, replays, lastStatusCode, lastError, nextAttemptAt, deliveredAt}` | `invalid_parameter` |
| `GET /{id}/deliveries/{deliveryId}` | — | `200 {delivery, payload, attempts[{attemptNumber, requestedAt, durationMs, statusCode, outcome, error, responseExcerpt}]}` | `webhook_delivery_not_found` |
| `POST /{id}/deliveries/{deliveryId}/replay` | — | `202` | `webhook_delivery_in_progress` (409) |
| `POST /{id}/replays` | `{statuses: [DEAD\|PAUSED\|SUCCEEDED\|DISCARDED], since}` | `202 {queued}` (up to 1000) | |

## What we send

```http
POST https://merchant.example/webhooks
Content-Type: application/json; charset=UTF-8
User-Agent: StoreForge-Webhooks/1.0
X-StoreForge-Topic: orders/paid
X-StoreForge-Event-Id: 01a11b20-050a-71c6-…      ← your deduplication key (same on retries and replays)
X-StoreForge-Delivery-Id: 01a11b20-0611-…
X-StoreForge-Store-Id: 01a11b1f-…
X-StoreForge-Api-Version: 2026-10
X-StoreForge-Timestamp: 1791456123
X-StoreForge-Signature: v1=5f2c…[,v1=<signature with the previous secret during rotation>]

{"id":"01a11b20-050a-71c6-…","topic":"orders/paid","apiVersion":"2026-10","storeId":"…","createdAt":"2026-10-08T10:31:02Z",
 "data":{"orderId":"…","orderNumber":1001,"name":"#1001","total":{"amount":"49.00","currency":"USD"},"customerId":"…","paymentMethod":"card"}}
```

- **Success:** any 2xx within **10 s** (total, including the body).
- **Retries:** timeouts, connection errors, 3xx (redirects are never followed), 408, 429 (`Retry-After` honoured as "not before") and 5xx. The schedule is 1m, 5m, 30m, 2h, 6h, 12h, 24h, 24h; after 9 attempts (~3 days) the delivery is **DEAD** and can be replayed.
- **Other 4xx:** given up after 3 attempts.
- **Pausing:** after 20 consecutive failed attempts the subscription is **paused** and its creator gets an email. Events are kept: fix the endpoint, `PATCH {"status":"ACTIVE"}`, then `POST /replays {"statuses":["PAUSED","DEAD"], "since": …}`.

## Receiving safely (checklist)

1. **Verify the signature on the raw body**, before parsing: `HMAC-SHA256(secret, timestamp + "." + rawBody)` as lowercase hex. Compare in **constant time** with **each** `v1=` value (there are two during a rotation).
2. **Reject old timestamps** (|now − timestamp| > 5 min): a captured request can't be replayed later.
3. **Deduplicate by `X-StoreForge-Event-Id`** (keep processed ids for a few days). You *will* get duplicates: retries after your timeout, and replays.
4. **Answer 2xx quickly** and process asynchronously. Slow answers time out and cause retries.
5. **Don't assume order.** Deliveries run in parallel and retries reorder them. We have observed `orders/paid` arriving before `orders/create`. Use the payload as a hint and fetch the current state from the API.
6. **Reconcile periodically** through the API: an endpoint that is down longer than ~3 days loses deliveries (until replayed).

Reference receiver (Python, standard library only), used in our own manual verification:

```python
import hashlib, hmac, time

def verify(raw_body: bytes, headers, secrets) -> bool:
    timestamp = headers["X-StoreForge-Timestamp"]
    if abs(time.time() - int(timestamp)) > 300:
        return False
    signatures = [p.strip()[3:] for p in headers["X-StoreForge-Signature"].split(",") if p.strip().startswith("v1=")]
    payload = timestamp.encode() + b"." + raw_body
    return any(hmac.compare_digest(hmac.new(s.encode(), payload, hashlib.sha256).hexdigest(), sig)
               for s in secrets for sig in signatures)
```

Java: see `WebhookDeliveryIT.verify` (`Mac` HmacSHA256 + `MessageDigest.isEqual`).
