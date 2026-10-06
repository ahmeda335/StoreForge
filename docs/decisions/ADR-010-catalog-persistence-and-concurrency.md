# ADR-010: JPA aggregates for writes, SQL read models for lists, HTTP optimistic concurrency

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P4

## Context
The catalog is the first module with a real aggregate (product + options + variants), list screens that grow with data, a public read API, and several staff members editing the same products.

## Problem
1. How do we load and save aggregates, and how do we serve list screens without N+1 queries?
2. How do we stop two staff members from silently overwriting each other's edits?
3. How are options and money stored?

## Options Considered
(1) JPA for everything with fetch joins/entity graphs (pagination + collection fetch join paginates **in memory**: Hibernate warning HHH90003004); JDBC for everything (lose dirty checking and cascades for aggregate writes); **JPA for writes + SQL read models for lists** (CQRS-lite); a separate read database (overkill).

(2) Last write wins (silent data loss); pessimistic locks held across user think-time (impossible over HTTP); **optimistic locking**: `@Version` in the DB, exposed as `ETag`/`If-Match`.

(3) Options as a separate table, as JSONB (Hibernate 7's JSON mapping and Jackson 3 support were uncertain), or **an `option_names text[]` on the product plus `option1..3` columns on variants** (Shopify-like, constrained to 3).

## Decision
1. Writes load the whole aggregate (`findByStoreIdAndId` with `@EntityGraph(variants)`) and change it through `Product` methods. Lists use `ProductQueries` (JdbcClient): the admin list is **one** query (a `LATERAL` aggregate for variant count/price range), the storefront list is **two** (page + `variants WHERE product_id = ANY(:ids)`). A test asserts the statement count is independent of page size.
2. Every product response carries `ETag: "v<version>"`. Writes accept `If-Match`: a mismatch gives **412 `precondition_failed`**. Writers that pass the check concurrently are caught by JPA `@Version` at flush, giving **409 `concurrent_modification`**. Variant changes `touch()` the root so the version covers the whole aggregate. `If-Match` is optional (a client that omits it opts into last-write-wins, which is documented).
3. `option_names text[]` (max 3) + `option1..3`, with uniqueness via `UNIQUE NULLS NOT DISTINCT (product_id, option1, option2, option3)`. Money is `bigint` minor units in the DB, the `Money` value object in code, and decimal **strings** on the wire. Currency is the store's.

## Why We Chose It
- Aggregates keep invariants in one place. Read models return exactly what screens need, with predictable query counts.
- `If-Match` makes "someone else changed this" a first-class, testable API answer.
- Option columns are simple, indexable, and constrained by the database.

## Trade-offs
- Two representations to keep consistent (entity mapping and SQL read models). This is mitigated by both being tested against the same schema.
- A strong ETag based on version means any change, even to a variant, invalidates the client's copy. That is intended (the aggregate is the unit of consistency).
- More than 3 options is impossible without a migration (an accepted product limit).

## Consequences
- New list endpoints get SQL read models plus a query-count test.
- New aggregate roots get `@Version`, `ETag`, `If-Match`, 412/409 tests.
- Inventory (P5) stays **outside** the product aggregate. Stock is a separate consistency boundary with different contention patterns (ADR-004).
