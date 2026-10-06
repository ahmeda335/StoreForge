# Optimistic Locking and ETags

**Problem.** Staff A and B open the same product. A changes the price, B fixes a typo in the title and saves second. Without protection, B's save writes B's (stale) copy of every field, so A's price change is **silently lost**. That is the lost-update problem stretched over minutes of human think-time.

**Why we need it.** Multi-staff stores; later, third-party apps editing products through the API.

**Alternatives.** Pessimistic locks (`SELECT FOR UPDATE` can't be held across HTTP requests; "check-out" locks need expiry and frustrate users); last-write-wins (data loss); field-level merging (complex, surprising).

**Decision.** A version number per aggregate (`@Version`), exposed as an `ETag`, enforced with `If-Match` ([ADR-010](../decisions/ADR-010-catalog-persistence-and-concurrency.md)).

**Mental model.**
- Every successful write increments `version`. Hibernate generates `UPDATE ... SET ..., version = 4 WHERE id = ? AND version = 3`. If 0 rows match, someone else committed first, and `ObjectOptimisticLockingFailureException` becomes **409**.
- HTTP makes this explicit: `GET` returns `ETag: "v3"`. The client sends `If-Match: "v3"` with its write. The server compares *before* changing anything: a mismatch gives **412 Precondition Failed**. The client reloads, shows the other change, and retries.
- **Two layers, two races:** `If-Match` catches the "think-time" race (minutes). `@Version` at flush catches the "same millisecond" race (both requests passed the `If-Match` check). `ProductAdminIT.concurrentEditsWithTheSameVersionNeverBothWin` shows both codes (412 and 409) appearing under 6 concurrent writers, with exactly one 200.
- **Aggregate-wide version:** changing a variant changes the product (`touch()`), so the product's ETag covers the whole aggregate. Otherwise two people could concurrently change two variants and the "≥1 variant" rule could break.
- **Strong vs. weak ETags:** `If-Match` uses strong comparison, so our ETags are strong (`"v3"`, not `W/"v3"`).

**Project usage.** `Product.version`, `ProductService.loadForUpdate`, `AdminProductsController.expectedVersion/etag`, `ApiExceptionHandler.handleOptimisticLock`.

**Failure cases.** Clients that never send `If-Match` (still last-write-wins; consider making it required with **428 Precondition Required** later). Retrying a 409 automatically without re-reading (it just overwrites). Forgetting to bump the root version on child changes.

**Testing.** Stale ETag → 412 and the first edit survives; concurrent writers → exactly one 200; variant change bumps the ETag.

**Observability.** 409/412 rates on product endpoints show contention (or a client bug).

**When NOT to use it.** Hot counters (stock levels, coupon usage): conflicts would be constant and retries would storm. Use atomic conditional updates there (ADR-004, P5).
