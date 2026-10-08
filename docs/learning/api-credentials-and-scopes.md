# API Credentials, Scopes and Versioned Contracts

**Problem.** Code outside the platform must act on a store: least privilege, instant revocation, an audit trail of *who* (which key or app) did what, and a contract that doesn't break integrations every time we refactor.

**Why we need it.** The developer API (P12): merchants' scripts and third-party apps.

**Alternatives.**
- **Reusing user sessions or JWTs:** all-or-nothing rights, and tied to a person.
- **JWT API tokens:** not revocable before expiry.
- **Separate permission systems for apps:** two authorization models drift apart.

**Decision.** Opaque, hashed, prefixed tokens. Scopes that *are* internal permissions. An `Actor` accepted by services. Date-versioned paths ([ADR-021](../decisions/ADR-021-developer-api-and-credentials.md)).

**Mental model.**
- **A credential = (store, scopes, revocation state).** Look it up on every request: that's the price of "revoked means revoked now".
- **Hash tokens like passwords, but quickly:** a 256-bit random token can't be guessed, so SHA-256 suffices and allows lookup. Passwords need slow hashes because humans choose them.
- **Prefixes (`sfk_`, `sfat_`)** identify leaked secrets for humans and scanners, and let the server reject garbage before touching the database.
- **Scope = permission, not a parallel world.** `read_orders` *is* `orders:read`. The same `StoreAccess` decides for users and for clients, and a credential can't exceed its grantor.
- **Declare protection where routes are declared** (`@RequiresScope`), and make the absence of a declaration a build failure (architecture test) and a runtime failure (fail closed).
- **Bind to one store twice:** the URL must match the credential's store, and the code reads the store from the *credential*, not from the URL. Even if the first check broke, no other store's data could be reached (EXP-016 D1).
- **Version the contract, not the code.** `/api/2026-10/...` with its own DTOs. Internal refactors don't change the payloads; breaking changes go to a new date.
- **Sync feeds beat webhooks for correctness:** `updatedAfter` + cursor, ordered by `(updated_at, id)`, is how an integration catches up after downtime.

**Project usage.** `developer.*` (`ApiClientPrincipal`, `ApiCredentialIntrospector`, `ApiKeyService`, `DeveloperApiInterceptor`, `UsageFilter`), `stores.Actor`, the `Developer*Controller` classes in catalog, orders, inventory and webhooks.

**Failure cases.**
- Caching credentials (revocation delayed);
- logging tokens;
- scopes beyond the grantor;
- endpoints added without scope checks;
- using the path's store id for data access;
- breaking payload changes in place;
- counting usage in an interceptor's `afterCompletion`, which skips rejected requests ([EXP-016](../experiments/EXP-016-developer-platform-mutations.md)).

**Testing.** `DeveloperApiIT` (the contract, the architecture rule, anti-escalation, revocation, sync feeds, idempotent writes, audit actor), mutation checks.

**Observability.**
- Usage per credential (`/admin/v1/stores/{id}/api-keys/{keyId}/usage`) instead of Prometheus labels (cardinality);
- audit actor types `API_KEY` / `APP`;
- `last_used_at`.

**When NOT to use it.** Purely internal service-to-service calls (mTLS / workload identity).
