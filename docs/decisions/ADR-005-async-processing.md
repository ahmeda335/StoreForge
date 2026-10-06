# ADR-005: Transactional outbox + PostgreSQL job queue before any broker

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P0 (implementation in P9)

## Context
Order placement, payments, emails and webhooks need reliable asynchronous processing. The dual-write problem (DB commit + publish) must be solved.

## Problem
How are events made reliable, and what transports them?

## Options Considered
1. In-memory Spring events: lost on crash.
2. Publish to RabbitMQ/Kafka after commit: lost if the process dies between commit and publish.
3. Spring Modulith's event publication registry: a ready-made outbox for in-process listeners; convenient but hides the mechanism we want to learn.
4. **Own outbox table + PostgreSQL job queue (`FOR UPDATE SKIP LOCKED`, leases, backoff, DEAD status).**
5. Outbox + broker.

## Decision
Option 4. A broker (RabbitMQ) only when measurements show the DB queue is the bottleneck or consumers move out of process; Kafka only for stream/replay use cases.

## Why We Chose It
- Enqueue is atomic with business data, for free.
- Zero new infrastructure; easy to inspect with SQL; easy to test with Testcontainers.
- Builds the exact mental model (at-least-once, leases, idempotent consumers) brokers also require.

## Trade-offs
- Polling latency (~0.5s) and DB load; table bloat needs cleanup (retention job, autovacuum tuning).
- Throughput ceiling lower than a broker's (fine for hundreds of jobs/sec).

## Consequences
- Every consumer is idempotent (inbox table or natural idempotency).
- Trace context is stored with outbox/job rows to keep traces connected.
- Metrics `jobs_oldest_age`, `outbox_unpublished` and DEAD counts are part of the implementation.
