# Module: platform (P14)

**Responsibility:** what StoreForge staff can do across tenants: find stores, suspend and reinstate them, look after the job queue, read the platform audit log. Authentication (password + mandatory TOTP, platform tokens) belongs to **identity**.

**Public API:** none (controllers only).
**Depends on:**
- `identity` (`PlatformAdminPrincipal`);
- `stores` (`StoreAdministration`);
- `jobs` (`JobAdministration`);
- `audit` (`PlatformAuditLog`).

**Decision:** [ADR-026](../decisions/ADR-026-platform-admin-and-mfa.md). **API:** [api/platform.md](../api/platform.md). **Runbook:** [platform-admin](../operations/runbooks/platform-admin.md).

## Becoming a platform session

```mermaid
sequenceDiagram
    participant S as Staff member
    participant A as /auth (identity)
    participant P as /platform/v1 step-up (identity)
    participant DB as PostgreSQL
    Note over DB: operator (owner role) INSERTs platform_admins row
    S->>A: POST /auth/v1/token (password)
    A-->>S: merchant token (aud merchant)
    S->>P: POST /mfa/enrollment (merchant token)
    P->>DB: seal + store TOTP secret
    P-->>S: secret + otpauth URI (once)
    S->>P: POST /mfa/enrollment/confirmation {code}
    P-->>S: 10 recovery codes (once; stored as SHA-256)
    S->>P: POST /session {code}
    P->>DB: UPDATE … SET totp_last_step = :step WHERE totp_last_step < :step (replay)
    P->>DB: platform_audit_log: platform.session_started
    P-->>S: platform token (aud platform, amr, 15 min)
    S->>P: GET /platform/v1/stores (platform token): re-checked every request
```

## Data (V18)
| Table | Notes |
|---|---|
| `platform_admins` | user, note, sealed TOTP secret, confirmed at, last accepted step, recovery code hashes; the app role can't INSERT/DELETE |
| `platform_audit_log` | admin, action, target, details, client IP, time; append-only for the app |

Store actions are also written to the store's own `audit_log` (actor `ADMIN`).

## Tests
`TotpTest` (RFC 6238 vectors), `PlatformAdminIT` (step-up, replay, recovery codes, audiences, suspension visible to the merchant, revocation, brute force, database privileges), `FunctionLevelAuthorizationIT` (no platform endpoint accepts a merchant token).

## Known limitations
No WebAuthn, no impersonation, no admin UI, no MFA for merchants yet. Granting, revoking and resets are SQL by an operator (by design, documented).
