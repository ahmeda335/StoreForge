# Events

Not implemented yet. Async processing arrives in **P9** (transactional outbox + PostgreSQL job queue, [ADR-005](../decisions/ADR-005-async-processing.md)).

Until then, the design lives in [blueprint/09-events-async.md](../blueprint/09-events-async.md): event catalog, envelope, at-least-once delivery semantics, idempotent consumers, retries/backoff, dead-lettering, ordering and versioning. This page becomes the as-built event catalog in P9.
