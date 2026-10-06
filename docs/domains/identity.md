# Module: identity (P2)

**Responsibility:** platform user accounts (merchants and staff), credentials, sessions and tokens. Customers are *not* users. They are store-scoped (P7).

**Public API** (`dev.storeforge.identity`): `UserPrincipal(userId)`, the authenticated caller, available in any controller via `@AuthenticationPrincipal`.
**Depends on:** `shared`, `notifications` (`EmailSender`).

## Data

| Table | Purpose | Key constraints |
|---|---|---|
| `users` | Account, password hash, verification, security stamp, lockout counters | `uq_users_email` on `citext`; `ck_users_status`; email ≤ 254 |
| `refresh_tokens` | Hashed refresh tokens grouped in families | `uq_refresh_tokens_token_hash`; FK → users (cascade) |
| `email_tokens` | Hashed single-use tokens for verification/reset | `uq_email_tokens_token_hash`; `ck_email_tokens_purpose` |

## Invariants and where they are enforced

| Invariant | Enforced by |
|---|---|
| One account per email, case-insensitively | `EmailAddress` normalization + `UNIQUE` on `citext` (races resolved by the constraint, see `RegistrationService`) |
| A refresh token rotates at most once | Conditional `UPDATE ... WHERE used_at IS NULL RETURNING` (`RefreshTokenStore.consume`) |
| Reuse of a rotated token kills the family | `TokenService.refresh` (revocation committed outside the failing request's transaction) |
| Email tokens are single-use, expiring, newest-only | `EmailTokenStore.consume` / `replace` |
| Every failed login is counted | Atomic `failed_login_count + 1` (`UserLoginCounters`), not entity read-modify-write |
| A password reset ends every session | Same transaction: password + `security_stamp++` + revoke all refresh tokens |
| Unverified users cannot get tokens | `LoginService` (after the password check) |

## Flows

```mermaid
sequenceDiagram
    participant B as Browser (SPA)
    participant A as /auth/v1
    participant DB as PostgreSQL
    participant M as Mail (after commit)
    B->>A: POST /registrations {email, password}
    A->>A: rate limit (IP, email) · policy · Argon2 hash
    A->>DB: TX: INSERT users, INSERT email_tokens(hash)
    A-->>M: VerifyEmail (or AccountAlreadyExists)
    A->>B: 202 (identical in every case)
    B->>A: POST /email-verifications/confirmation {token}
    A->>DB: TX: consume token, set email_verified_at
    B->>A: POST /token {email, password}
    A->>DB: lockedUntil? verify hash · on failure: failed_login_count+1
    A->>DB: INSERT refresh_tokens(new family)
    A->>B: 200 {access_token} + Set-Cookie sf_refresh (HttpOnly, Strict, Path=/auth/v1/token)
```

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as /auth/v1/token/refresh
    participant DB as PostgreSQL
    B->>A: POST + cookie + X-Requested-With (Origin checked)
    A->>DB: TX: UPDATE refresh_tokens SET used_at WHERE hash AND unused AND valid RETURNING
    alt row returned
        A->>DB: INSERT new token (same family) · COMMIT
        A->>B: 200 new access token + new cookie
    else used token presented again (reuse)
        A->>DB: UPDATE ... SET revoked_at WHERE family_id (own commit)
        A->>B: 401 invalid_refresh_token (WARN logged with userId, familyId)
    else unknown / expired / revoked
        A->>B: 401 invalid_refresh_token
    end
```

Authenticating an API request (`/admin/**`): `BearerTokenAuthenticationFilter` → `NimbusJwtDecoder` (ES256 only, our JWKS, RFC 9068 validators) → `UserTokenConverter` (user ACTIVE, `sstamp` matches) → `UserPrincipal` → `UserMdcFilter` puts `userId` into the logs.

## Security notes
Account enumeration resistance (registration, reset, resend, login), hashing parity (dummy hash for unknown emails), per-IP + per-email rate limits, temporary exponential lockout, secrets hashed at rest, redacted `toString()` on every DTO/event carrying secrets, tokens never in URL paths or logs (tested).

## Tests
`RegistrationIT`, `LoginIT`, `TokenRefreshIT`, `AccessTokenSecurityIT`, `PasswordResetIT`, `SensitiveDataLoggingIT`, `EmailAddressTest`.

## Known limitations
- Emails are sent synchronously after commit, without durability (P9 → outbox).
- Rate-limit buckets are per instance (P12 → Redis).
- No MFA (P14), no breached-password check (P14).
- An SMTP server that accepts connections but hangs blocks registration for up to the SMTP timeouts (2s connect, 5s read/write). P9 removes email sending from the request path.
