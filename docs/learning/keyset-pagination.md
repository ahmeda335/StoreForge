# Keyset (Cursor) Pagination

**Problem.** `LIMIT 25 OFFSET 10000` makes PostgreSQL produce and discard 10,000 rows, so it gets slower with every page. When rows are inserted while a client pages, offsets shift and items are skipped or shown twice.

**Why we need it.** Audit logs, products, orders: every admin list grows without bound.

**Alternatives.** Offset pagination (simple, fine for small fixed sets); page-number UIs over offset; search-engine scroll APIs.

**Decision.** Opaque cursors over a unique, indexed sort key `(timestamp, id)`, `limit` default 25 and max 100. The response shape is `{data, page: {nextCursor, hasMore}}` ([blueprint 08](../blueprint/08-api-design.md#pagination-filtering-sorting-searching)).

**Mental model.**
- Next page: `WHERE (occurred_at, id) < (:lastTime, :lastId) ORDER BY occurred_at DESC, id DESC LIMIT n+1`. The row comparison maps directly onto the index `(store_id, occurred_at DESC, id DESC)`, so cost is constant per page.
- The `id` tiebreaker makes the order total: two rows with the same timestamp still have a defined order, so nothing is skipped.
- Fetch `limit + 1` rows: the extra row says whether `hasMore` is true, without a `COUNT(*)`.
- The cursor is opaque (base64url) so clients don't build them. Malformed cursors are a 400 `invalid_cursor`.
- Trade-off: no "jump to page 37" and no total count (for counts, use a separate, possibly approximate, query when the UI truly needs it).

**Project usage.** `shared.pagination.Cursor`, `Page`; `JdbcAuditLog.list`; `GET .../audit-log`.

**Failure cases.** Sorting by a non-unique column without a tiebreaker (skips rows). Sort keys that change while paging (e.g. `updated_at`). Rows may move between pages, which is acceptable for change feeds but must be documented. Missing a matching index (the query is still correct, just slow).

**Testing.** `AuditLogIT`: two pages of 2 cover 4 entries with no duplicates, newest first, `hasMore` correct; malformed cursor → 400.

**Observability.** Query duration per list endpoint (P6 traces). It should stay flat as data grows (P15 load test with large audit logs).

**When NOT to use it.** Small, bounded lists (roles, members): return them whole.
