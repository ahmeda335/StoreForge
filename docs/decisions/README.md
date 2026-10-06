# Architecture Decision Records

An ADR captures a decision that someone (including future you) would ask "why?" about in six months.
Template: [TEMPLATE.md](TEMPLATE.md). Status values: Proposed · Accepted · Superseded by ADR-xxx.

| ADR | Title | Status | Phase |
|---|---|---|---|
| [001](ADR-001-modular-monolith.md) | Modular monolith with enforced module boundaries | Accepted | P0 |
| [002](ADR-002-multi-tenancy-strategy.md) | Shared schema, store = tenant, PostgreSQL RLS | Accepted | P0 (implemented P3) |
| [003](ADR-003-authentication-strategy.md) | JWT access tokens + rotating opaque refresh tokens | Accepted, refined by 008 | P0 (implemented P2) |
| [004](ADR-004-inventory-concurrency.md) | Atomic conditional updates + CHECK constraints for stock | Accepted | P0 (implemented P5) |
| [005](ADR-005-async-processing.md) | Transactional outbox + PostgreSQL job queue before any broker | Accepted | P0 (implemented P9) |
| [006](ADR-006-foundation-stack.md) | Foundation stack: Java 21, Spring Boot 4, Maven, Flyway, separate DB roles | Accepted | P1 |
| [007](ADR-007-error-and-correlation-model.md) | RFC 9457 problem details + request IDs | Accepted | P1 |
| [008](ADR-008-token-details-and-revocation.md) | Access-token format (RFC 9068), security-stamp revocation, strict refresh rotation | Accepted | P2 |
