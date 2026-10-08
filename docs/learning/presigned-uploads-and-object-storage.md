# Object Storage and Presigned Uploads

**Problem.** Files (images) are big, slow to upload from phones, and not relational data. Streaming them through app instances wastes threads and memory; storing them in PostgreSQL bloats backups.

**Why we need it.** Product images (P13); later exports, invoices, app assets.

**Alternatives.**
- **`bytea` in the database:** transactional, but heavy.
- **Local disk:** breaks with more than one instance.
- **Proxy uploads through the app:** simple auth, but bytes on our instances.
- **Presigned URLs to an S3-compatible store:** the client talks to storage directly; we only sign and verify.

**Decision.** Presigned PUT, verified completion, tenant-prefixed keys ([ADR-024](../decisions/ADR-024-object-storage-and-presigned-uploads.md)).

**Mental model.**
- A **presigned URL** is a request signed in advance with our credentials (AWS SigV4): method, bucket, key, expiry, and **any headers we choose to sign**. Storage recomputes the signature from the actual request; a different header value means 403. Sign `Content-Type` and `Content-Length`, and the store enforces the declaration.
- **Signatures prove what was allowed, not what was sent as content.** A script can be PUT with `Content-Type: image/png`. Look at the stored bytes: **magic numbers** (PNG `89 50 4E 47…`, JPEG `FF D8 FF`, WebP `RIFF….WEBP`). Read 12 bytes with a ranged GET, not the whole file.
- **Two-phase lifecycle:** a PENDING row before the upload, READY after verification. The database row is the truth; the object is a referenced blob.
- **Keys are ours:** `stores/{store}/products/{product}/{image}`. The client never chooses a path, so it can't overwrite or address another tenant's file.
- **Deletion is a side effect:** delete the row in the transaction, delete the object after commit through a retried job. Sweep PENDING rows that were abandoned.
- **SVG is code** (XML with scripts). Don't serve user SVGs from your domain.

**Project usage.** `storage` module (`ObjectStorage`, `S3ObjectStorage`, `ObjectDeletionJob`), `ProductImageService`, `ImageUploadSweeper`, `ImageSignatures`, V17.

**Failure cases.**
- Unsigned length (any size accepted), trusting the declared type, client-chosen keys, public buckets with guessable keys, deleting the object before the transaction commits (row kept, file gone), never cleaning up abandoned uploads.
- Storage calls inside a database transaction (a slow store holds locks).

**Testing.** `ProductImagesIT` against a real S3-compatible server (SeaweedFS): upload/verify/download, spoofed type, size/type enforced by the store, limits, other tenant, sweep, delete; mutations in [EXP-019](../experiments/EXP-019-image-upload-safeguards.md).

**Observability.** `storeforge_storage_requests_seconds{operation, outcome}`, `storeforge_catalog_image_uploads_abandoned_total`, DEAD `storage.delete-object` jobs.

**When NOT to use it.** Tiny files that must be transactional with the row (a few KB): store them in the database. Files that need server-side processing anyway (virus scanning, transcoding): upload to a quarantine bucket and process before accepting.
