# JWT Access Tokens

**Problem.** Every API request must prove who sent it. Looking up a session in a database on every request is simple but couples every service to that store. Self-contained tokens can be verified with a public key alone.

**Why we need it.** The merchant SPA calls the admin API; later other services and third parties verify our tokens. Learning token validation and its pitfalls is a project goal (ADR-003).

**Alternatives.** Server-side sessions (simpler revocation, CSRF-prone cookies); opaque tokens + introspection (DB/cache lookup per request; we use these for third-party apps in P12); PASETO (safer by design, less ecosystem support).

**Decision.** Short-lived (15 min) **ES256-signed JWTs** in the RFC 9068 profile ([ADR-008](../decisions/ADR-008-token-details-and-revocation.md)).

**Mental model.**
- A JWT is `base64url(header).base64url(claims).signature`. Anyone can **read** it; only the holder of the private key can **create** a valid signature. Never put secrets in claims.
- **Validation is a checklist, and the token never decides it** (RFC 8725):
  1. Algorithm pinned by the server (ES256). The header's `alg` is not trusted. This defeats `alg: none` and the HS256-with-public-key confusion attack.
  2. Signature verified with a key from *our* JWKS, chosen by `kid`.
  3. `typ: at+jwt`, so an ID token or other JWT can't be replayed as an access token.
  4. `iss`, `aud` (`merchant`), `client_id`, `exp`/`iat`/`nbf` with small clock skew.
- **Revocation is the weak spot** of stateless tokens. Ours: short TTL + a per-request user check (ACTIVE + `sstamp`). That check costs one PK lookup, which gives up part of the statelessness for immediate revocation. It is a conscious trade.
- **Key rotation:** the `kid` in the header selects the key. Old public keys stay in the JWKS for one TTL after rotation.

**Project usage.** `AccessTokenIssuer` (mint), `SecurityConfiguration.jwtDecoder()` (validate), `UserTokenConverter` (account checks), `JwtKeys` (active + previous keys), `GET /auth/v1/jwks.json`.

**Configuration.** `storeforge.auth.issuer`, `access-token-ttl`, `signing-jwk`, `previous-public-jwks`, `allow-ephemeral-signing-key`.

**Failure cases.** Ephemeral key + restart → all tokens invalid. Multiple instances each with an ephemeral key → tokens only work on the instance that issued them. Clock skew between servers → spurious expiry. Permissions embedded in tokens → stale for the whole TTL (we don't do this).

**Testing.** `AccessTokenSecurityIT`: each attack starts from a token that *would* be valid and changes one thing. A sanity test proves the helper's correctly signed tokens are accepted, so the rejections are meaningful.

**Observability.** 401s on `/admin/**` in HTTP metrics. `WWW-Authenticate` error descriptions in responses. `userId` on authenticated log lines.

**When NOT to use it.** When you need instant revocation without any lookup, or for long-lived credentials: use opaque tokens.
