# Events and asynchronous work (as built, P9)

Design: [blueprint/09-events-async.md](../blueprint/09-events-async.md). Decisions: [ADR-005](../decisions/ADR-005-async-processing.md), [ADR-016](../decisions/ADR-016-async-infrastructure.md). Module: [domains/jobs.md](../domains/jobs.md).

## Delivery semantics (the contract every consumer relies on)

| Property | Guarantee | How |
|---|---|---|
| Event created iff business change committed | **Atomic** | `Outbox.append` in the business transaction (`MANDATORY`) |
| Event → jobs | **Exactly once** per (handler, event) | Relay runs in one DB transaction; `UNIQUE (type, dedup_key)` backstop |
| Job execution | **At least once** | Lease expiry re-runs jobs of crashed/hung workers; retries after failures |
| Effects | **Once**, if the consumer is idempotent | Dedup keys, the inbox (`processed_messages`), state checks |
| Ordering | **None** between jobs | Retries and multiple workers reorder; use state machines/versions where order matters |
| Latency | ~0.5 s poll + handler time when healthy | `JOBS_POLL_INTERVAL` |
| Failures | Retry with exponential backoff + jitter (5 s doubling, cap 1 h), **DEAD after 10 attempts** or a `PermanentJobFailure` | `JobWorker`, `/actuator/jobs` to inspect and requeue |

## Event envelope

`Event(eventId, storeId, aggregateType, aggregateId, eventType, eventVersion, occurredAt, payload)`. `eventId` (UUIDv7) is the deduplication key. Trace context (`traceparent`) travels with the event and its jobs.

## Event catalog

| Event | v | Producer | Payload | Consumers | Idempotency |
|---|---|---|---|---|---|
| `OrderPlaced` | 1 | orders (`DefaultOrders.place`, inside checkout completion) | `orderId, number, totalMinor, currency, customerId` | `order-confirmation-emails` → email | Email dedup key `order-confirmation:<orderId>` |
| `OrderPaid` | 1 | orders (manual payment; provider payments in P10) | `orderId, number, totalMinor, currency, customerId, method` | `customer-purchase-stats` → `customers.orders_count/total_spent_minor` | Inbox `(store, consumer, eventId)` |
| `OrderCancelled` | 1 | orders (merchant cancel, payment timeout) | `orderId, number, reason` | — (webhooks: P11) | — |
| `OrderFulfilled` | 1 | orders | `orderId, number` | — (shipping email / webhooks later) | — |

## Direct jobs (not events)

| Job type | Enqueued by | Payload | Handler |
|---|---|---|---|
| `email.send` | `Notifications.enqueueEmail` (identity verification/reset/account-exists, staff invitation, order confirmation) | `messageId` | `EmailSendJob`: send via SMTP, mark SENT, erase sensitive body |

## Rules for new events and consumers
1. Payload = ids + routing values; consumers re-read tenant data inside `inStore`.
2. Never change a payload in place: add fields compatibly, or publish a new `eventVersion` (and keep consumers tolerant of unknown fields).
3. Every consumer has a stable `name()` (part of job types and inbox keys) and an explicit idempotency strategy, listed in this table.
4. Throw `PermanentJobFailure` only when retrying cannot help.
