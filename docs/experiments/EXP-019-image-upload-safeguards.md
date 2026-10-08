# EXP-019: Do the image upload safeguards hold? (real store, mutations)

- **Date:** 2026-10-08 · **Phase:** P13 · **Setup:** Testcontainers PostgreSQL + SeaweedFS 3.97; `ProductImagesIT`. Manual run against Compose.

## Experiment
1. Upload attempts that break the declaration: a bigger file than signed, a different Content-Type than signed, a script declared as `image/png`, completion before any upload.
2. Remove one safeguard at a time:
   - **M1:** no file-signature check at completion;
   - **M2:** Content-Length not part of the presigned signature;
   - **M3:** the sweeper ignores the age of PENDING uploads.
3. Manual: register, create a store and product, upload with a plain HTTP client to the presigned URL, complete, open the storefront image URL.

## Expected behavior
- The store refuses size/type mismatches itself; the script is discarded with 422 and its object deleted; early completion is `409 upload_missing`.
- Each mutation fails its test.

## Actual behavior

| Case | Result |
|---|---|
| PUT 5,000 bytes to a URL signed for 100 | refused by SeaweedFS (4xx) |
| PUT with `text/html` to a URL signed for `image/png` | refused (4xx) |
| Script declared as PNG | `422 upload_rejected`; row gone; object deleted after the job ran |
| Complete before upload | `409 upload_missing`; completing after the real upload works |
| M1 | Failed (the script became a READY image) |
| M2 | Failed (the bigger file was accepted) |
| M3 | Failed (a fresh upload was swept) |
| Manual | start 201 (`PUT`, headers to send), PUT 200, complete 200 position 1, storefront URL → 200, 208 bytes; `storeforge_storage_requests{operation=head|read,outcome=success}` |

Also: a member of another store gets **404** (not 403) for the image endpoints, like every admin endpoint (my first assertion expected 403: test bug).

## What failed / why
Nothing in the product. MinIO itself was not available (see [ADR-024](../decisions/ADR-024-object-storage-and-presigned-uploads.md)); the experiment ran on SeaweedFS.

## How it was detected
N/A.

## How the system recovered
N/A.

## What we changed
Nothing. The behaviour is guarded by `ProductImagesIT` (7 tests) and `ImageSignaturesTest`.
