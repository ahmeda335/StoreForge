# Runbook: Webhooks

## Symptoms
- A merchant reports missing webhooks, or got the email "Webhook endpoint paused".
- `storeforge_webhooks_deliveries_total{outcome="retry"|"dead"}` rising; `storeforge_webhooks_subscriptions_paused_total` increasing.
- `outcome="blocked"`: deliveries refused by the SSRF guard. The URL now resolves to a private address: misconfiguration or an attack.
- Emails or payment events slow while webhooks are busy: check that the `webhooks.deliver` bulkhead is configured (`storeforge.jobs.type-concurrency`).

## Diagnose
1. Merchant view: `GET …/webhook-subscriptions/{id}` (status, `consecutiveFailures`, `lastSuccessAt`/`lastFailureAt`), then `…/deliveries?status=DEAD|RETRYING|PAUSED`, then one delivery's `attempts` (status code, duration, error, 512-byte response excerpt).
2. Typical patterns:

   | Pattern | Likely cause |
   |---|---|
   | `timeout` ~10 s | The receiver processes synchronously (tell them: answer 2xx, then work) |
   | 401/403 | The receiver rejects our signature (rotated secret not updated? clock skew?) |
   | 404/410 | The endpoint was moved |
   | 3xx | The URL changed (we never follow redirects) |
   | `destination not allowed` | DNS points to a private range |

3. Logs: WARN "Webhook delivery attempt failed" with `deliveryId`, `host`, `httpStatus`, `outcome`, linked to the producing request by `traceId`.

## Act
- **Receiver fixed:** `PATCH {"status":"ACTIVE"}`, then `POST /replays {"statuses":["PAUSED","DEAD"],"since":"…"}`. Receivers dedup by event id, so over-replaying is safe.
- **Signature problems after a rotation:** the old secret works for 24 h. After that, the merchant must use the new one, or rotate again.
- **`blocked`:** never "allow" it by relaxing `allowed-private-cidrs` in production. Ask the merchant for a public endpoint, and review the audit log (`webhook.subscription_created/updated`) for who set the URL.
- **Encryption key compromised:** rotate `WEBHOOK_ENCRYPTION_KEY`. There's no automatic re-encryption yet, so ask merchants to rotate their webhook secrets, which re-seals them with the new key.

## Notes
- Delivery history is kept for 30 days (`purge_webhook_deliveries`, hourly).
- One instance sends at most 4 webhooks concurrently, and at most 4 connections per endpoint.
