# 09 — Events and Asynchronous Processing

## The problem

`placeOrder()` must (1) save the order and (2) trigger an email, webhooks and payment processing. If you publish to a broker/queue *after* commit and crash in between, the event is **lost**. If you publish *before* commit and the transaction rolls back, you announced an order that **does not exist**. The DB and the queue cannot share one transaction (2PC/XA is fragile and mostly unavailable).

## Transactional outbox

```mermaid
sequenceDiagram
    participant S as OrderService
    participant DB as PostgreSQL
    participant R as Outbox relay (poller)
    participant H as Handlers (jobs)
    S->>DB: BEGIN
    S->>DB: INSERT orders ...
    S->>DB: INSERT outbox_events(type=OrderPlaced, payload)
    S->>DB: COMMIT  (both or neither)
    loop every 500ms
        R->>DB: SELECT ... WHERE published_at IS NULL ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 100
        R->>DB: INSERT jobs (one per subscribed handler) ; UPDATE outbox SET published_at=now()
    end
    H->>DB: claim job (SKIP LOCKED), run handler, mark done / schedule retry
```

`outbox_events(id uuid v7, store_id, aggregate_type, aggregate_id, event_type, event_version, payload jsonb, occurred_at, published_at, trace_context)`.

`trace_context` stores the W3C `traceparent` so the worker's span links to the originating request (end-to-end traces across async).

## DB job queue vs. message broker

**Start with a PostgreSQL-backed job queue** (`jobs` table + `FOR UPDATE SKIP LOCKED`). See [ADR-005](../decisions/ADR-005-async-processing.md).

| Use DB queue when | Introduce a broker (RabbitMQ) when | Introduce Kafka when |
|---|---|---|
| Throughput is ≲ hundreds of jobs/sec | Job throughput makes polling/table bloat measurable (vacuum pressure, poll latency) | You need a **replayable log** (many independent consumers, reprocessing history), event streaming/CDC to analytics, >10k events/sec |
| Producers and consumers are in the same deployable | Consumers move into separate services/languages | Multiple teams consume the same stream |
| You want enqueue atomic with business data (free!) | You need routing/fan-out features, priority queues, per-consumer backpressure | Ordered processing per key (partition by store_id) at scale |

The outbox stays even with a broker: the relay then publishes to the broker instead of the jobs table.

## Delivery semantics

**At-least-once**, everywhere. A handler may run twice: worker crashed after doing the work but before marking the job done, a lease expired, or the relay crashed after inserting jobs but before marking the outbox row. Therefore **every consumer is idempotent**:

- **Natural idempotency:** "set status to PAID if not already".
- **Inbox / processed-messages table:** `processed_messages(consumer, message_id) PRIMARY KEY`, inserted in the same transaction as the consumer's effect. A duplicate hits the PK → skip.
- **Idempotency keys toward external APIs:** e.g. Stripe `Idempotency-Key = job_id`, email provider message id = `event_id + template`.

Exactly-once *processing* is approximated by at-least-once delivery + idempotent effects. Exactly-once *delivery* does not exist across a network.

## Retries, backoff, DLQ

- Job row: `attempts`, `max_attempts` (default 10), `run_at`, `last_error`, `locked_by`, `locked_until` (lease).
- Retry delay: `min(base * 2^attempt, cap) + random jitter` (base 5s, cap 1h). Jitter prevents synchronized retry storms.
- Error classification: **retryable** (timeouts, 5xx, connection refused) vs. **permanent** (validation, 4xx except 408/429). Permanent errors go straight to `DEAD`.
- **Dead letter** = `status = 'DEAD'` rows + metric + admin endpoint to inspect and **requeue**. No silent drops.
- Lease: a crashed worker's job becomes claimable again after `locked_until`. This is why handlers must be idempotent.

## Ordering

The global order of events is neither guaranteed nor needed. Where order matters (payment status updates for one payment), use:
1. **State machines that reject invalid transitions** (a `PaymentCaptured` arriving before `PaymentAuthorized` is handled: captured implies authorized), and
2. **Monotonic versions** (`aggregate_version` in the event, and the consumer ignores older versions).
Per-aggregate ordering via a single-flight lock (`job.ordering_key` processed serially) is available for the few cases that need it.

## Event versioning

Envelope: `{ eventId, eventType, eventVersion, occurredAt, storeId, aggregateId, data }`. Rules: adding optional fields = same version. Removing or renaming = new `eventVersion` and producers emit both versions during migration. Consumers tolerate unknown fields. Internal events (between modules) are versioned separately from **public webhook payloads** (versioned by API version).

## Event catalog

| Event | Producer | Consumers | Payload (data) | Idempotency | Retry / failure |
|---|---|---|---|---|---|
| `UserRegistered` | identity | notifications (verification email) | userId, email | email per (event, template) once | Retry; DEAD → admin resends |
| `StoreCreated` | stores | audit, (billing) | storeId, ownerId | Natural | Retry |
| `ProductUpdated` | catalog | cache invalidation, search index, webhooks `products/update` | productId, changed fields, version | Version check | Cache: TTL fallback if invalidation is lost |
| `InventoryReserved` | inventory | (analytics) | checkoutId, items | Reservation unique per (checkout, item) | — |
| `InventoryReleased` | inventory | webhooks `inventory_levels/update` | itemId, available | — | Retry |
| `InventoryLevelChanged` | inventory | webhooks, low-stock notification | itemId, available, onHand | Version | Retry |
| `OrderPlaced` | orders | payments (create payment), notifications (confirmation), webhooks `orders/create`, customers (stats) | orderId, number, totals, items, customerId | Each consumer uses the inbox | Payment failure → order stays PENDING; expiry job cancels |
| `PaymentAuthorized` | payments | orders (financial_status) | paymentId, orderId, amount | State machine | Retry |
| `PaymentCaptured` | payments | orders → PAID, inventory commit, webhooks `orders/paid`, notifications | paymentId, orderId, amount | State machine | Retry |
| `PaymentFailed` | payments | orders (mark, maybe cancel), inventory release, notifications | paymentId, reason code | State machine | Retry |
| `OrderCancelled` | orders | inventory release, payments void/refund, discounts restore, webhooks | orderId, reason | Release keyed by reservation status | Compensation must succeed eventually, so the job retries until DEAD → alert |
| `RefundSucceeded` | payments | orders (financial_status), webhooks `refunds/create`, notifications | refundId, amount | Provider refund id unique | Retry |
| `WebhookDeliveryRequested` | webhooks (fan-out from any public event) | webhook dispatcher | deliveryId | `UNIQUE(subscription, event)` | Backoff up to 24–72h, then DEAD + disable subscription after N consecutive failures |
| `EmailRequested` | notifications (internal job) | email sender | messageId | `email_messages.id` | Retry; provider idempotency |
| `CheckoutExpired` | jobs (sweeper) | inventory release | checkoutId | Reservation status | Retry |

## In-process events?

Spring's `ApplicationEventPublisher` is used only for **non-critical, same-transaction** reactions (e.g. evicting a local cache after commit via `@TransactionalEventListener(AFTER_COMMIT)`, accepting that a crash may skip it, with TTL as the fallback). Anything that must happen goes through the outbox.
