# Runbook: Object storage (product images) and storefront search

## Symptoms
- Merchants can't add images: `upload_missing` although they uploaded, **503 `storage_unavailable`** (storage down or answering 5xx; `Retry-After: 30`), 502 from the proxy on the PUT to the storage host, other 5xx on `…/images/uploads` or `…/completion`, or storage returns 403 on the PUT.
- `storeforge_storage_requests_seconds_count{outcome="error"}` rising; DEAD jobs of type `storage.delete-object`.
- Storefront images broken (403 / expired URLs).
- Storefront search slow or timing out (`QueryTimeoutException` in logs, p95 of `/storefront/v1/search` above budget).

## Diagnose
1. **Storage reachable?** `curl -s -o /dev/null -w '%{http_code}' $STORAGE_ENDPOINT` (403 = up and enforcing auth). App logs for `S3Exception` with status and request id.
2. **PUT refused with 403:** the client sent a different `Content-Type` or body size than declared (expected behaviour), or the URL expired (10 min), or the storage server's clock is off. Compare the request with `upload.headers`.
3. **`upload_missing` after an upload:** the client uploaded to a different host than the app checks: `STORAGE_PUBLIC_ENDPOINT` and `STORAGE_ENDPOINT` must point at the same store and bucket.
4. **`upload_rejected`:** the stored size differs from `sizeBytes`, or the file isn't the declared format (magic bytes). Ask for the file; it's usually a renamed format (HEIC/AVIF as .png).
5. **Broken storefront images:** URLs are valid 1 h. Clients that cache the URL itself will break; they must re-read the product.
6. **Slow search:** `EXPLAIN (ANALYZE)` **as `storeforge_app` inside a tenant transaction** (see [EXP-018](../../experiments/EXP-018-search-at-scale-under-rls.md)). Check: the plan uses `search_product_matches`; no `Rows Removed by Filter` in the hundreds of thousands; catalog size of the store (`SELECT count(*) FROM products WHERE store_id = …`); `ANALYZE products` after bulk imports.

## Act
- **Storage outage:** restore it; uploads can be retried by clients (start again). SeaweedFS's S3 API answers ~10 s after the container starts. Shopping is unaffected (EXP-025); **WAL archiving and base backups also go to this storage**: check `SELECT failed_count, last_failed_time FROM pg_stat_archiver` after a long outage. Segments wait in `pg_wal` until archived, so a long outage grows the database disk. DEAD deletion jobs can be retried once it's back ([dead-jobs](dead-jobs.md)).
- **Orphan objects** (an object under `stores/{store}/products/…` without a `product_images` row): safe to delete. Never delete objects that still have a row.
- **Search:** for one huge catalog, consider the facet cache described in [ADR-025](../../decisions/ADR-025-search-in-postgresql-and-no-cache.md) (not built). Don't disable RLS on `products` to "make it fast".
- **Never** make the bucket public or reuse its credentials outside the app.

## Notes
- Abandoned uploads (PENDING > 1 h) are removed every 15 min (`storeforge_catalog_image_uploads_abandoned_total`).
- Local storage is SeaweedFS (`docker compose up -d seaweedfs`, `localhost:8333`); credentials in `infra/seaweedfs/s3.json` are for development only.
