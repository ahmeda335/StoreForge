# Module: storage (P13)

**Responsibility:** object storage behind presigned URLs: sign uploads and downloads, inspect stored objects, delete them (now or reliably after commit).

**Public API:** `ObjectStorage` (`presignUpload`, `presignDownload`, `head`, `readPrefix`, `delete`, `deleteLater`).
**Depends on:** `jobs` (deletion job), `shared`.
**Used by:** catalog (product images).
**Decision:** [ADR-024](../decisions/ADR-024-object-storage-and-presigned-uploads.md). **Learning:** [presigned uploads](../learning/presigned-uploads-and-object-storage.md).

## Product image upload (catalog + storage)

```mermaid
sequenceDiagram
    participant M as Merchant client
    participant A as StoreForge (catalog)
    participant S as Object storage (S3 API)
    M->>A: POST …/images/uploads {contentType, sizeBytes, alt}
    A->>A: type in JPEG/PNG/WebP, size ≤ 10 MB, ≤ 20 images (product row lock)
    A->>A: INSERT product_images PENDING, key stores/{s}/products/{p}/{i}
    A-->>M: 201 {imageId, upload: {url, PUT, headers, expiresAt (10 min)}}
    M->>S: PUT url (Content-Type + Content-Length signed)
    S-->>M: 200 (403 if size/type differ)
    M->>A: POST …/images/{id}/completion
    A->>S: HEAD key, GET bytes=0-11 (outside any transaction)
    alt size and magic bytes match
        A->>A: UPDATE → READY, position
        A-->>M: 200 image {url (signed, 1 h)}
    else
        A->>A: DELETE row + enqueue storage.delete-object
        A-->>M: 422 upload_rejected
    end
```

## Configuration (`storeforge.storage.*`)
`endpoint` (app → S3), `public-endpoint` (in presigned URLs, for clients), `region`, `bucket`, `access-key`, `secret-key`, `path-style`, `create-bucket` (local/test only). Locally: SeaweedFS from `compose.yaml` on `localhost:8333`.

## Data (V17, catalog)
`product_images`: PENDING/READY, `object_key` (CHECK: exactly the tenant-prefixed shape), type, size, alt, position; RLS. `stores_with_abandoned_image_uploads` definer function for the sweeper.

## Observability
`storeforge_storage_requests_seconds{operation=head|read|delete, outcome}`, `storeforge_catalog_image_uploads_abandoned_total`, DEAD `storage.delete-object` jobs ([runbook](../operations/runbooks/object-storage-and-search.md)).

## Tests
`ImageSignaturesTest`, `ProductImagesIT` (7, real SeaweedFS), image endpoints in `TenantIsolationIT`; mutations in [EXP-019](../experiments/EXP-019-image-upload-safeguards.md).

## Known limitations
No thumbnails or re-encoding, no reordering endpoint, no developer-API access to images, no CDN (signed URLs expire after 1 h).
