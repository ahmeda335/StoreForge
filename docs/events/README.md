# Events and asynchronous work (as built, P9)

Design: [blueprint/09-events-async.md](../blueprint/09-events-async.md). Decisions: [ADR-005](../decisions/ADR-005-async-processing.md), [ADR-016](../decisions/ADR-016-async-infrastructure.md). Module: [domains/jobs.md](../domains/jobs.md).

## Delivery semantics (the contract every consumer relies on)

| Property | Guarantee | How |
|---|---|---|
| Event created iff business change committed | **Atomic** | `Outbox.append` in the business transaction (`MANDATORY`) |
| Event → jobs | **Exactly once** per (handler, event) | Relay runs in one DB transaction; `UNIQUE (type, dedup_key)` backstop |
| Job execution | **At least once** | Lease expiry re-runs jobs of crashed/hung workers; retries after failures |
| Effects | **Once**, if the consumer is idempotent | Dedup keys, the inbox (`processed_messages`), state checks |
| Ordering | **None** between jobs (and jobs run concurrently since P11, ADR-019) | Retries and multiple workers reorder; use state machines/versions where order matters |
| Latency | ~0.5 s poll + handler time when healthy | `JOBS_POLL_INTERVAL` |
| Failures | Retry with exponential backoff + jitter (5 s doubling, cap 1 h), **DEAD after 10 attempts** or a `PermanentJobFailure` | `JobWorker`, `/actuator/jobs` to inspect and requeue |

## Event envelope

`Event(eventId, storeId, aggregateType, aggregateId, eventType, eventVersion, occurredAt, payload)`. `eventId` (UUIDv7) is the deduplication key. Trace context (`traceparent`) travels with the event and its jobs.

## Event catalog

| Event | v | Producer | Payload | Consumers | Idempotency |
|---|---|---|---|---|---|
| `OrderPlaced` | 1 | orders (`DefaultOrders.place`, inside checkout completion) | `orderId, number, totalMinor, currency, customerId` | `order-confirmation-emails` → email | Email dedup key `order-confirmation:<orderId>` |
| `OrderPaid` | 1 | orders (manual payment, or a provider capture via `Orders.applyPayment`; `method` = `manual` \| `card`) | `orderId, number, totalMinor, currency, customerId, method` | `customer-purchase-stats` → `customers.orders_count/total_spent_minor` | Inbox `(store, consumer, eventId)` |
| `OrderCancelled` | 1 | orders (merchant cancel, payment timeout) | `orderId, number, reason` | `payment-cancellations` → cancel the open payment at the provider (or capture + compensate if it already succeeded) | Payment state check + provider key `cancel:<paymentId>` |
| `OrderRefunded` | 1 | orders (`Orders.applyRefund`, merchant refunds only) | `orderId, number, refundId, amountMinor, currency, refundedTotalMinor` | — (webhooks: P11) | — |
| `OrderFulfilled` | 1 | orders | `orderId, number` | `webhooks` | Delivery `UNIQUE (subscription, event)` |
| `InventoryLevelChanged` | 1 | inventory (adjustments; stock sold at payment) | `inventoryItemId, variantId, onHand, available, delta, reason` | `webhooks` (`inventory_levels/update`) | Delivery `UNIQUE (subscription, event)` |

The `webhooks` consumer subscribes to every order event, `OrderRefunded` and `InventoryLevelChanged`, and maps them to public topics ([domains/webhooks.md](../domains/webhooks.md#topics-api-version-2026-10)).

## Direct jobs (not events)

| Job type | Enqueued by | Payload | Handler |
|---|---|---|---|
| `webhooks.deliver` | `WebhookFanout` (first attempt), `DeliveryService` (each retry, delayed 1m…24h; replays) | `deliveryId` | `DeliveryService.attempt`: signed POST, record attempt, schedule next / DEAD / pause. Bulkhead: 4 concurrent per instance |
| `payments.provider-event` | `WebhookIntake` (after signature check + `provider_events` dedup) | normalized provider event | `PaymentService.apply`: capture / decline / cancel / refund status, idempotent state checks |
| `payments.refund` | `RefundService` (merchant refunds: due after 30 s as a safety net; compensations: immediately) | `refundId` | `RefundService.execute`: refund or retrieve at the provider with key `refund:<refundId>`; retries while the provider reports it pending |
| `email.send` | `Notifications.enqueueEmail` (identity verification/reset/account-exists, staff invitation, order confirmation) | `messageId` | `EmailSendJob`: send via SMTP, mark SENT, erase sensitive body |

## Rules for new events and consumers
1. Payload = ids + routing values; consumers re-read tenant data inside `inStore`.
2. Never change a payload in place: add fields compatibly, or publish a new `eventVersion` (and keep consumers tolerant of unknown fields).
3. Every consumer has a stable `name()` (part of job types and inbox keys) and an explicit idempotency strategy, listed in this table.
4. Throw `PermanentJobFailure` only when retrying cannot help.
