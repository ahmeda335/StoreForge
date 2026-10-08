# Catalog API (implemented, P4)

Try it with [`http/catalog.http`](../../http/catalog.http).

## Merchant admin: `/admin/v1/stores/{storeId}/products`

Permissions: `products:read` for GET, `products:write` for everything else. Product responses carry `ETag: "v<version>"`. Send it as `If-Match` on writes to get **412 `precondition_failed`** instead of overwriting someone else's change.

| Method & path | Body | Success | Notable errors |
|---|---|---|---|
| `POST /products` | `{handle?, title, description?, vendor?, productType?, status?, options: [names], variants: [{sku?, price, compareAtPrice?, options: [values], weightGrams?}]}` | `201` + `Location` + `ETag` | `handle_taken`, `sku_taken`, `duplicate_variant` (409); `invalid_product`, `validation_failed` (422) |
| `GET /products?status=&vendor=&q=&sort=-createdAt\|createdAt&limit=&cursor=` | — | `200 {data: [{id, handle, title, status, vendor, variantCount, minPrice, maxPrice, ...}], page}` | `invalid_parameter` (400) |
| `GET /products/{id}` | — | `200` + `ETag` | `product_not_found` |
| `PATCH /products/{id}` | any of `{handle, title, description, vendor, productType, status}` | `200` + new `ETag` | `precondition_failed` (412), `concurrent_modification` (409), `handle_taken` |
| `POST /products/{id}/variants` | variant | `201` full product | `duplicate_variant`, `sku_taken`, `invalid_product` |
| `PATCH /products/{id}/variants/{variantId}` | any of `{sku, price, compareAtPrice, options, weightGrams}` | `200` full product | `variant_not_found`, 412/409 |
| `DELETE /products/{id}/variants/{variantId}` | — | `200` full product | `last_variant` (409) |

- **Money:** request prices are decimal strings in the store's currency (`"19.99"`; at most the currency's decimal places). Responses use `{"amount": "19.99", "currency": "USD"}`.
- **Status:** `DRAFT` (default, hidden from the storefront), `ACTIVE`, `ARCHIVED`.
- **Handle:** derived from the title when omitted (`"Blue Tee!"` → `blue-tee`).

## Public storefront: `/storefront/v1/products`

The store is chosen by the **hostname**: `{handle}.storeforge.localhost` (or a verified custom domain). Locally, send `X-Forwarded-Host: acme.storeforge.localhost`, or browse `http://acme.storeforge.localhost:8080` (most systems resolve `*.localhost` to 127.0.0.1).

| Method & path | Success | Errors |
|---|---|---|
| `GET /storefront/v1/products?limit=&cursor=` | `200 {data: [{id, handle, title, description, vendor, productType, options, variants: [{id, title, price, compareAtPrice, options}]}], page}` | `store_not_found` (unknown/inactive host) |
| `GET /storefront/v1/products/{handle}` | `200` product | `product_not_found` (also for drafts and other stores' products) |

Only ACTIVE products. No SKUs, statuses or internal timestamps. No authentication. Each variant has `available` (in stock or untracked).

## Storefront search (P13): `GET /storefront/v1/search`

| Parameter | Meaning |
|---|---|
| `q` | Words (≤ 200 chars). Web-search syntax: `"brass lamp"`, `lamp or light`, `-shade`. Accents ignored; typos in titles tolerated. Empty = browse everything |
| `vendor`, `productType` | Exact filters |
| `minPrice`, `maxPrice` | Decimal amounts; a product matches if any variant price is in range |
| `sort` | `relevance` (default; newest when no `q`), `price_asc`, `price_desc`, `newest` |
| `limit`, `offset` | 1–50 (default 20), 0–1,000 |

`200 {data: [{id, handle, title, vendor, productType, minPrice, maxPrice}], facets: {vendors: [{value, count}], productTypes: [{value, count}]}, page: {limit, offset, total}}`. Facets are disjunctive: vendor counts ignore the vendor filter (and type counts the type filter). Errors: `invalid_parameter`, `store_not_found`.

Storefront products also include `images: [{url, alt}]`. Image URLs are signed and expire after 1 hour: don't store them.

## Product images (P13): `/admin/v1/stores/{storeId}/products/{productId}/images`

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /uploads` | `{contentType: image/jpeg\|image/png\|image/webp, sizeBytes (≤ 10 MB), alt?}` | `201 {imageId, upload: {url, method: PUT, headers: {Content-Type, Content-Length}, expiresAt}}` (`products:write`) | `invalid_image` (422), `too_many_images` (409, 20 incl. uploads in progress), `product_not_found` |
| *(client)* `PUT {upload.url}` | the file, with exactly those headers, within 10 min | storage answers 200 (403 if size or type differ) | |
| `POST /{imageId}/completion` | — | `200 {id, url, alt, position, contentType, sizeBytes}` (idempotent) | `upload_missing` (409), `upload_rejected` (422: size or file signature don't match; the upload is discarded), `image_not_found` |
| `GET /` | — | `200 {data: [image]}` READY images by position (`products:read`) | — |
| `DELETE /{imageId}` | — | `204` (the file is deleted shortly after) | `image_not_found` |

Uploads not completed within an hour are removed automatically.
