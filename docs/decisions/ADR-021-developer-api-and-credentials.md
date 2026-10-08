# ADR-021: Developer API: opaque credentials, scopes as permissions, Actor, date versions

- **Status:** Accepted
- **Date:** 2026-10-08
- **Phase:** P12

## Context
Merchants' own scripts and third-party apps need to act on a store without a user's session. They must be limited to what was granted, be revocable at once, be observable, and see a stable contract.

## Problem
1. **Token format:** JWT or opaque?
2. **Authorization:** how do scopes relate to the permissions the admin API already enforces?
3. **Services:** how do they authorize a non-user caller without duplicating every method?
4. **Contract:** how is it versioned and kept separate from the admin API?

## Options Considered
1. **Token format:** JWT (stateless, not revocable until expiry) vs. **opaque tokens looked up per request**.
2. **Scopes:** an independent scope model checked in controllers vs. **scopes mapped 1:1 to internal permissions, checked by the same `StoreAccess`**.
3. **Services:** duplicate service methods for apps vs. **an `Actor` (user | API client) accepted by services**.
4. **Versioning:** a header vs. **a date in the path** (`/api/2026-10/...`), plus contract DTOs separate from the admin API.

## Decision
1. **Opaque bearer tokens:**
   - `sfk_` API keys, `sfat_` app tokens, 256 random bits each;
   - stored as SHA-256 in `api_credentials` (a directory table: looked up before the store is known);
   - shown once, with a display prefix;
   - looked up on every request, with **no cache**, so revocation is effective on the next request;
   - `last_used_at` written at most once a minute.

   SHA-256 rather than Argon2: high-entropy tokens can't be brute-forced, and lookup needs a deterministic hash.
2. **Scopes:** `read_products` … `read_customers`, each equal to one `Permission`.
   - **Anti-escalation:** a credential can't get scopes its creator (key) or approving merchant (app) doesn't hold, checked at creation and again at OAuth token issuance.
   - **`@RequiresScope` on every `/api/**` handler.** An architecture test fails the build otherwise; a missing annotation at runtime fails closed (500).
3. **`Actor`** (stores module): `StoreAccess.require(Actor, store, permissions...)` decides for users (membership and role) and API clients (own store, scope permissions). The audit log records `USER`, `API_KEY` or `APP`.

   Developer controllers take the store from the **credential**, never from the URL. The interceptor still requires the URL's store to match (404 otherwise).
4. **`/api/{version}/stores/{storeId}/...`** with `2026-10` as the only version:
   - an unknown version → 404 `unsupported_api_version`;
   - `X-StoreForge-Api-Version` echoed;
   - contract DTOs live next to each module's controller;
   - OpenAPI generated for `/api/**` only (`/developer/openapi`).

   One interceptor applies, in order: version, store binding, rate limit, scope. A filter counts usage with the final status.

## Why We Chose It
- **Immediate revocation and uninstall** are product requirements; an opaque lookup gives them trivially, for one indexed query per request.
- **One authorization model:** a scope can never mean more than the permission it maps to, and both APIs share one decision point.

## Trade-offs
- **A DB lookup per request** (cacheable later with a short TTL, at the cost of up to that TTL of revocation delay).
- **Path versions** duplicate routes when a second version arrives (not before).
- **The `Actor` overloads** exist only where the developer API needs them so far.

## Consequences
- **New developer endpoints:** live in their module, take the store from the principal, declare `@RequiresScope`, and use `Actor` overloads.
- **Breaking contract changes** ship only in a new dated version.
