# N+1 Queries and Read Models

**Problem.** Load 25 products, then for each product its variants are loaded lazily: 1 + 25 queries. Each one is fast, but together they add up to 26 round trips, pool time, and latency that grows with page size. It is invisible in development with 3 products and painful in production.

**Why we need it.** Every list screen (catalog, orders, customers) and every storefront page.

**Alternatives.** Lazy loading (the problem itself); `JOIN FETCH`/`@EntityGraph` on a collection **with pagination**, which makes Hibernate fetch everything and paginate in memory (HHH90003004); `@BatchSize` (fewer queries, still entity-heavy); **SQL read models** that return exactly the screen's data.

**Decision.** Aggregates (JPA) for writes and single-item reads (one fetch-joined query). SQL read models (`ProductQueries`) for lists, with a statement-count test ([ADR-010](../decisions/ADR-010-catalog-persistence-and-concurrency.md)).

**Mental model.**
- Count queries by **shape**, not by speed: the question is "does the number of queries depend on the number of rows?"
- Admin list: one query. A `CROSS JOIN LATERAL (SELECT count(*), min(price), max(price) ...)` computes per-product aggregates inside the same statement.
- Storefront list: page query + one `WHERE product_id = ANY(:ids)` query, grouped in memory. That is two statements for any page size.
- A read model is a deliberate, small duplication: the SQL knows which columns the list shows. It is CQRS-lite, with the same database and no separate store.

**Project usage.** `ProductQueries.adminPage`, `activePage`, `variantsOf`. `ProductRepository.findByStoreIdAndId` uses `@EntityGraph("variants")`.

**Failure cases.** Adding a lazy association to a DTO mapping "just once". Serializing entities directly (Jackson triggers lazy loads, or fails outside the transaction). `open-in-view=true` would hide this by keeping the session open during serialization; we keep it off.

**Testing.** `StorefrontCatalogIT.listQueryCountIsIndependentOfPageSize` counts statements through a proxied `DataSource` (`QueryCounter`). Mutation-tested: an N+1 version takes 18 statements instead of 9, and the test fails.

**Observability.** From P6, traces show each JDBC statement as a span: an N+1 looks like a comb of identical spans.

**When NOT to use it.** Write paths: use the aggregate, so invariants live in one place. Don't hand-write SQL updates that bypass domain rules.
