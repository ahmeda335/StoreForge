# Transactional Outbox

**Problem.** Placing an order must (1) save the order and (2) tell other parts of the system (send a confirmation, update stats, later webhooks and payments). These are two writes to two systems: the database, and a queue or mail server. They can't commit together:
- publish **after** commit, crash in between → the event is **lost** (order without email);
- publish **before** commit, the transaction rolls back → an event for an order that **doesn't exist**.

Two-phase commit (XA) across a DB and a broker is fragile, slow and often unavailable.

**Why we need it.** Every asynchronous reaction to a business change: emails (P9), payments follow-up (P10), webhooks (P11), search indexing and caches (P13).

**Alternatives.** After-commit listeners (what P2–P8 did for email: lost on crash or SMTP failure, [EXP-002](../experiments/EXP-002-email-provider-unavailable.md)); publish-then-commit (phantom events); XA; change data capture from the WAL (Debezium: powerful, heavy, P18).

**Decision.** Write the event as a row **in the same transaction** as the business change. A relay moves committed events into the job queue ([ADR-005](../decisions/ADR-005-async-processing.md), [ADR-016](../decisions/ADR-016-async-infrastructure.md)).

**Mental model.**
- The database transaction makes "order saved" and "event recorded" **one fact**: both or neither.
- Consumers no longer depend on the producer's process surviving: the event waits in a table until handled.
- The relay is "at least once" in general (crash after publishing, before marking published). Here it publishes into the *same database*, so it's one transaction and effectively once. **But consumers are still at-least-once**: a worker can die after the effect and before marking the job done. Hence idempotent consumers, always.
- Three ways to be idempotent: a **natural key** (`order-confirmation:<orderId>`: the second email row is rejected), an **inbox** (record the event id in the same transaction as the effect), a **state check** (skip if already SENT).
- **Thin events:** carry ids, not copies of data. The consumer re-reads current data under RLS. That means less PII in queue tables and no stale copies.
- The trace continues: the producer's `traceparent` is stored with the event, so the consumer's span is a child of the original request.

**Project usage.** `jobs.Outbox` / `JdbcOutbox`, `OutboxRelay`, `OrderEvents`, `DefaultOrders.place`, `OrderService` transitions, `OrderConfirmationEmails`, `CustomerPurchaseStats`. Emails use the same idea directly: `email_messages` row + job in the caller's transaction.

**Failure cases.** Forgetting the transaction (`MANDATORY` makes it an error, tested); non-idempotent consumers (mutation-tested: double counting); fat payloads that go stale or leak PII; event schema changes in place (version them); unbounded growth (housekeeping).

**Testing.** `OutboxIT` (rolled back → no event; relay re-run → no duplicate jobs; trace continuity), `ReliableEffectsIT` (redelivery → one email, stats once), [EXP-008](../experiments/EXP-008-async-guarantees-mutations.md).

**Observability.** `storeforge_outbox_unpublished` (should hover near 0; growing means the relay is down), job metrics, the `job event:<consumer>` spans in the producer's trace.

**When NOT to use it.** Purely in-process, non-critical reactions such as evicting a local cache (a plain listener is fine). Or work that the request must finish synchronously before it can answer.
