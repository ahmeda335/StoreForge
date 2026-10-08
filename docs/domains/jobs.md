# Modules: jobs (outbox + job queue) and notifications (P9)

**jobs** (public API: `Outbox`, `Jobs`, `EventHandler`, `JobHandler`, `Event`, `Inbox`, `PermanentJobFailure`) depends only on `shared`. **notifications** (API: `Notifications`, `EmailMessage`, `EmailSender`) depends on `jobs`. Producers: identity, stores (emails), orders (events). Consumers: orders (confirmation email), customers (purchase stats).

## Flow

```mermaid
sequenceDiagram
    participant S as Business service (one transaction)
    participant DB as PostgreSQL
    participant R as OutboxRelay (every 500ms)
    participant W as JobWorker (every 500ms)
    participant H as Handler
    S->>DB: UPDATE/INSERT business rows
    S->>DB: INSERT outbox_events (+ traceparent)   or   INSERT email_messages + jobs
    S->>DB: COMMIT  (all or nothing)
    R->>DB: BEGIN; SELECT unpublished FOR UPDATE SKIP LOCKED
    R->>DB: INSERT jobs (one per subscribed handler, ON CONFLICT DO NOTHING); mark published; COMMIT
    W->>DB: UPDATE jobs SET RUNNING, lease … WHERE id IN (… FOR UPDATE SKIP LOCKED) RETURNING  (commit)
    W->>H: handle(job) inside span "job <type>" (child of producer trace)
    H->>DB: own transaction(s), tenant work via inStore(storeId)
    alt success
        W->>DB: DONE (only if still owner of the lease)
    else failure
        W->>DB: READY at now + backoff  |  DEAD (attempts exhausted / permanent)
    end
```

## Data (V8)

| Table | Kind | Notes |
|---|---|---|
| `outbox_events` | Infrastructure | Partial index on unpublished; `trace_parent` |
| `jobs` | Infrastructure | `UNIQUE (type, dedup_key)`; partial indexes for due READY and expired leases; `event_id` links to the event |
| `email_messages` | Infrastructure | `UNIQUE (dedup_key)`; `body` NULL after sending when `sensitive` |
| `processed_messages` | Tenant data (RLS) | Inbox: `(store_id, consumer, message_id)` |
| `customers.orders_count`, `total_spent_minor` | Tenant data | Maintained by the stats consumer |

## Operations
- `GET :8081/actuator/jobs`: counts by status, unpublished events, oldest ready age, latest 50 DEAD jobs. `POST :8081/actuator/jobs/{id}`: requeue a DEAD job.
- Housekeeping (hourly): DONE jobs and published events (only if all their jobs are DONE) after 7 days; SENT emails after 30 days; idempotency keys after 24 h.
- Config: `storeforge.jobs.*` (poll 500 ms, batch 50, concurrency 8, `type-concurrency` bulkheads, lease 60 s, max attempts 10, backoff 5 s → 1 h, retention 7 d). Scheduling can be disabled (`storeforge.scheduling.enabled=false`, as tests do).

## Tests
`BackoffTest`, `JobQueueIT` (retries with backoff, DEAD + operator requeue, permanent failure, lease reclaim after a simulated crash, **4 concurrent workers × 60 jobs → each executed once**, log lines asserted), `OutboxIT` (rollback leaves no event, outside a transaction is refused, relay re-run creates no duplicate jobs, **job span in the request's trace**), `ReliableEffectsIT` (**SMTP outage → retried and delivered**, sensitive body erased, redelivered `OrderPlaced` → one email, redelivered `OrderPaid` → stats counted once, housekeeping). Mutation-checked ([EXP-008](../experiments/EXP-008-async-guarantees-mutations.md)).

## Known limitations
- Since P11 ([ADR-019](../decisions/ADR-019-concurrent-jobs-and-bulkheads.md)): jobs run concurrently (8 per instance) with per-type bulkheads (`webhooks.deliver`: 4); the original sequential worker caused head-of-line blocking and lease-expiry duplicates ([EXP-012](../experiments/EXP-012-head-of-line-blocking.md)).
- No ordering; no per-tenant fairness in the queue yet (one store's flood can delay others: P15/P17 noisy-neighbour work).
- Consumers today: emails, customer stats, payments (provider events, refunds, cancellations: P10), webhooks (fan-out and deliveries: P11). See the [event catalog](../events/README.md).
