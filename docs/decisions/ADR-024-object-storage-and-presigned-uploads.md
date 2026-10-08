# ADR-024: Object storage on the S3 API, presigned uploads verified on completion, SeaweedFS locally

- **Status:** Accepted
- **Date:** 2026-10-08
- **Phase:** P13
- **Evidence:** [EXP-019](../experiments/EXP-019-image-upload-safeguards.md); `ProductImagesIT`, `ImageSignaturesTest`

## Context
Products need images. Files don't belong in PostgreSQL (size, backups, bandwidth), and streaming uploads through the application ties up instances and memory for the duration of a slow mobile upload.

## Problem
1. Where do files live, and how do clients get them there?
2. How do we stop a client from storing something other than what it declared (a script disguised as `image/png`, a 2 GB file)?
3. How do tenants stay separated in one bucket?
4. What cleans up uploads that never finish, and objects whose rows are gone?

## Options Considered
1. **Storage:** `bytea` in PostgreSQL; the local filesystem; **an S3-compatible object store**.
2. **Upload path:** multipart POST to the app; **presigned PUT directly to storage**.
3. **Validation:** trust the declared Content-Type; **sign type and length into the URL, then inspect the stored object (size, file signature) before accepting it**; full decoding/re-encoding of images.
4. **Local/test server:** MinIO; LocalStack; Adobe S3Mock; **SeaweedFS**.

## Decision
1. **S3 API through the AWS SDK v2** (blocking Apache client, Netty excluded), behind a small `ObjectStorage` interface in a `storage` module. The bucket is private.
2. **Three steps:**
   1. `POST …/images/uploads {contentType, sizeBytes, alt}`: only JPEG/PNG/WebP (no SVG: it can carry scripts), 1 B–10 MB, at most 20 images per product (uploads in progress count; product row lock). A PENDING row and a PUT URL valid 10 minutes, signed for **this key, this Content-Type and this Content-Length**.
   2. The client PUTs to storage.
   3. `POST …/images/{id}/completion`: HEAD + a 12-byte ranged GET, outside any transaction. Size must equal the declaration and the magic bytes must match the type; otherwise the row and object are discarded (`422 upload_rejected`). Then READY with a position.
3. **Keys are chosen by the server:** `stores/{store}/products/{product}/{image}`, and a CHECK constraint makes the database reject any other shape. Rows are under RLS.
4. **Cleanup:** deleting a row enqueues a retried `storage.delete-object` job in the same transaction; a sweeper removes PENDING rows older than 1 hour (definer function for "which stores", then per store under RLS, ADR-011).
5. **Downloads:** presigned GET URLs valid 1 hour, in admin and storefront responses.
6. **SeaweedFS 3.97** in Compose and Testcontainers. **Redesign note:** the plan said MinIO, but MinIO no longer publishes community images (Docker Hub and quay.io tags are gone; `chainguard/minio` is `latest`-only, which breaks our pinning rule). Nothing in the code depends on the server: production can use AWS S3, MinIO, or anything S3-compatible.

## Why We Chose It
- Bytes never touch an instance; uploads scale with the storage service.
- The signature makes the store itself refuse a different size or type, and the completion check catches content that lies about its type. Measured: both are enforced by SeaweedFS and caught by mutations (EXP-019).
- Server-chosen, prefixed keys mean a URL can never address another tenant's object.

## Trade-offs
- The magic-byte check proves the *format*, not that the image is well-formed or harmless to an image decoder. Re-encoding (thumbnails) would be the stronger step; not done.
- Signed download URLs expire hourly: clients must not cache the URL itself, and CDN caching is per URL. A public CDN path would be the production step.
- Two round trips for the client (upload, then complete).

## Consequences
- Storage outages fail uploads and completions (errors, metrics `storeforge_storage_requests`) but no other feature. Reads only sign URLs (no network).
- Orphan objects without rows can only appear if a deletion job dies permanently; the dead-jobs runbook covers it.
