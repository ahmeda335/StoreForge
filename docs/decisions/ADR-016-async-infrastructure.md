# ADR-016: Outbox and job queue as platform infrastructure; durable email; thin events

- **Status:** Accepted (implements [ADR-005](ADR-005-async-processing.md), refines [ADR-011](ADR-011-cross-tenant-background-work.md))
- **Date:** 2026-10-07
- **Phase:** P9

## Context
ADR-005 chose a transactional outbox plus a PostgreSQL job queue. Implementing it raised questions about tenancy, email durability, payload contents and ordering.

## Problem
1. Workers must find work in every tenant. Do `outbox_events`/`jobs` get RLS?
2. How do emails (often created by identity, before any tenant exists) become durable?
3. What do event payloads contain?
4. Exactly how often does a consumer run, and in what order?

## Options Considered
1. RLS on queue tables with definer functions for every worker query, vs. **queue tables as platform infrastructure without RLS**, touched only by the jobs/notifications modules.
2. Emails as outbox *events* consumed by a mailer, vs. **an `email_messages` row + send job written in the caller's transaction**. The email row is its own outbox.
3. Fat events (full order, addresses, emails) vs. **thin events** (ids + routing values; consumers re-read under RLS).
4. "Exactly once" (impossible across a crash between effect and acknowledgment) vs. **at-least-once with idempotent consumers**.

## Decision
1. `outbox_events`, `jobs`, `email_messages` carry `store_id` but **no RLS**. They join the two directory tables on the RLS meta-test's explicit allow-list. Every *tenant-data* effect of a handler runs in `TenantTransactions.inStore(event.storeId())`. `processed_messages` (inbox) is tenant data **with** RLS.
2. `Notifications.enqueueEmail(storeId?, message, dedupKey, sensitive)` is `MANDATORY`. Identity and invitation mailers became **synchronous** `@EventListener`s that enqueue inside the publishing transaction. Bodies containing single-use links are marked `sensitive` and set to NULL once sent.
3. Payloads are thin records in the producer's public API (`OrderEvents.Placed`, …) with an `eventVersion`.
4. Delivery is at-least-once. Consumers are idempotent via dedup keys (confirmation email per order), the inbox (purchase stats), or state checks (SENT emails are skipped). **No ordering guarantee** between jobs.
5. Idempotency-key cleanup across tenants uses a **delete-only** definer function returning a count. This extends ADR-011's "ids only" rule to "no data leaves the function".

## Why We Chose It
- One database, so enqueueing is atomic with the business change and the relay step is itself one transaction. Only the *consumer* side needs idempotency.
- No privileged functions on the hot path. The worker's queries are plain SQL on infrastructure tables.
- Thin events keep personal data out of the queue tables and avoid stale copies of tenant data.

## Trade-offs
- Queue tables hold some tenant-related data without RLS. This is mitigated by thin payloads, module ownership, and no API exposure except the operator endpoint on the private port.
- Email is at-least-once (SMTP has no idempotency key): a crash between "sent" and "marked SENT" re-sends.
- Polling (500 ms) adds latency and constant small DB load. The partial indexes keep each poll cheap.
- In-database queues add vacuum pressure at high volume. Measure in P15 before considering a broker (P18).

## Consequences
- New async work = an `EventHandler` (for domain events) or a `JobHandler` (direct jobs), always idempotent, with tenant work inside `inStore`.
- Changing a payload means a new `eventVersion`. Consumers must tolerate unknown fields.
- Operators watch `storeforge_jobs_oldest_ready_age_seconds` and DEAD counts. Runbook: [dead-jobs](../operations/runbooks/dead-jobs.md).
