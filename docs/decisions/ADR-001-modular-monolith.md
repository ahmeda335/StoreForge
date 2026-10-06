# ADR-001: Modular monolith with enforced module boundaries

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P0

## Context
StoreForge is a multi-tenant commerce platform built by one developer as a learning project. The domain has many areas (identity, stores, catalog, inventory, checkout, orders, payments, webhooks, apps) with strong transactional coupling at checkout (stock + order + event must commit together).

## Problem
Should the system start as microservices, a classic monolith, or a modular monolith?

## Options Considered
1. **Microservices from day one.** Pros: independent deploys and scaling; "realistic". Cons: distributed transactions at checkout (sagas everywhere), N pipelines, network failures between every module, eventual consistency where none is needed; boundaries guessed before the domain is understood.
2. **Classic layered monolith** (`controllers/`, `services/`, `repositories/` at the top level). Pros: simplest. Cons: no boundaries; everything can call everything; extraction later is a rewrite.
3. **Modular monolith.** One deployable, packages per business module, public API vs. internals, boundaries verified by tests.

## Decision
Option 3. Package-by-module under `dev.storeforge.<module>`, internals in `<module>.internal` (or sub-packages), with **Spring Modulith** `ApplicationModules.verify()` run as a test in CI.

## Why We Chose It
- ACID transactions across checkout → inventory → orders for free.
- Boundaries are enforced *mechanically*, so they don't erode.
- Distributed-systems learning still happens at real boundaries (payment provider, webhooks, email, workers), where the problems are genuine.
- Extraction remains possible: modules talking via outbox events can move out of process later.

## Trade-offs
- One deployable: a bad deploy affects everything; no independent scaling of modules (mitigated by api/worker roles of the same image).
- Cross-module foreign keys are convenient but are a coupling to undo on extraction.

## Consequences
- Every new module declares its public API types at its root package.
- Cross-module calls go through those types only; tests fail otherwise.
- Service extraction requires a documented trigger (see blueprint 31-E).
