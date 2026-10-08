# Module: webhooks (P11)

**Responsibility:** delivering store events to merchants' systems: subscriptions, topic mapping, signing, retries, history, replay, secret rotation, pausing failing endpoints, the SSRF guard.
**Public Java API:** none (it consumes events). **Depends on:** `jobs` (EventHandler, Jobs), `stores` (StoreAccess, `webhooks:manage`), `notifications`, `identity` (UserDirectory for the pause email), `audit`, `orders` / `inventory` (event constants only), `shared`.
**Decisions:** [ADR-018](../decisions/ADR-018-outbound-webhooks.md), [ADR-019](../decisions/ADR-019-concurrent-jobs-and-bulkheads.md). **API and receiver guide:** [api/webhooks.md](../api/webhooks.md).

## Flow

```mermaid
sequenceDiagram
    participant D as Domain transaction (orders / inventory)
    participant O as Outbox + relay
    participant F as WebhookFanout (event:webhooks)
    participant J as Job: webhooks.deliver
    participant S as WebhookSender
    participant R as Merchant endpoint
    D->>O: OrderPaid / InventoryLevelChanged … (same tx as the change)
    O->>F: job per event
    F->>F: tenant tx: subscriptions for topic → INSERT delivery (UNIQUE sub+event, body frozen) + job
    J->>J: tenant tx: load delivery, decrypt secret(s), check subscription status
    J->>S: POST (no transaction held)
    S->>S: DNS via EndpointPolicy (public addresses only) → connect → no redirects → deadline
    S->>R: X-StoreForge-* headers, v1=HMAC(timestamp.body)
    R-->>S: 2xx / error / nothing
    J->>J: tenant tx: attempt row; SUCCEEDED, or RETRYING + delayed job (1m…24h), or DEAD; failures++ → pause at 20
```

## States
- **Subscription:** `ACTIVE` → `PAUSED_FAILING` (after N consecutive failed attempts; email to the subscriber) → `ACTIVE` (merchant re-activates; counter reset). `DISABLED` (merchant) gets no deliveries.
- **Delivery:**
  - `PENDING → SUCCEEDED`;
  - `PENDING → RETRYING → … → SUCCEEDED | DEAD`;
  - `PAUSED` (subscription paused);
  - `DISCARDED` (subscription disabled);
  - replay: any final state → `PENDING` (same event id, `replays + 1`).

## Topics (API version `2026-10`)

| Topic | From internal event | `data` |
|---|---|---|
| `orders/create` | `OrderPlaced` | `orderId, orderNumber, name, total{amount,currency}, customerId` |
| `orders/paid` | `OrderPaid` | same + `paymentMethod` |
| `orders/cancelled` | `OrderCancelled` | `orderId, orderNumber, name, reason` |
| `orders/fulfilled` | `OrderFulfilled` | `orderId, orderNumber, name` |
| `fulfillments/create` | `OrderShipped` (P13) | `orderId, orderNumber, name, shipmentId, carrier, trackingNumber, complete` |
| `refunds/create` | `OrderRefunded` | `orderId, orderNumber, name, refundId, amount, refundedTotal` |
| `inventory_levels/update` | `InventoryLevelChanged` (adjustments, sales) | `variantId, inventoryItemId, onHand, available, delta, reason` |

## Data (V10)

| Table | Notes |
|---|---|
| `webhook_subscriptions` | `topics text[]`, status, `secret_ciphertext` (AES-GCM), previous secret + expiry (rotation overlap), failure counters; FK to `stores` |
| `webhook_deliveries` | `UNIQUE (subscription_id, event_id)` (idempotent fan-out); `payload text` (exact bytes); attempts in the current cycle, `replays`, next attempt, last status/error |
| `webhook_delivery_attempts` | Per attempt: number, time, duration, status code, outcome, error, 512-byte excerpt; never request headers |
| `purge_webhook_deliveries(cutoff)` | Delete-only definer function; finished deliveries older than 30 days (hourly) |

All three tables have RLS.

## Tests
- **`EndpointPolicyTest` (29 cases):** private, metadata, CGNAT, IPv6 ULA and link-local; mapped, NAT64, 6to4 and decimal encodings; mixed answers; credentials; DNS rebinding; the escape hatch.
- **`SecretBoxTest`:** round trip, tamper, wrong row, wrong key.
- **`RetryPolicyTest`:** schedule ~70 h, 4xx limit, `Retry-After` floor and cap, jitter.
- **`WebhookDeliveryIT`** (12 tests, WireMock or raw-socket receivers; the receiver verifies signatures independently):
  - validation, permissions, secret shown once;
  - order and inventory events signed, with the event id as dedup key;
  - a redelivered internal event → one webhook;
  - 503 → retry → success with identical bytes;
  - slow receiver; dripping receiver cut by the total deadline; redirect not followed; 429 `Retry-After`; 4xx → DEAD after 3;
  - replay of DEAD (409 while in flight); pause, email, parked events, resume, bulk replay;
  - rotation overlap and expiry;
  - a destination turned private after validation is never contacted.
- **`JobQueueIT.slowJobsAreBulkheadedAndDoNotDelayOtherWork`.**
- Mutation-checked in [EXP-013](../experiments/EXP-013-webhook-mutations.md).

## Known limitations
- No ordering guarantee (by design; see the receiver guide). No per-tenant fairness in the job queue.
- No endpoint "test ping" button; no app-owned subscriptions (P12).
- Encryption-key rotation not automated; the pause threshold counts attempts, not time.
