# Platform API (implemented, P14): StoreForge staff only

Design: [domains/platform.md](../domains/platform.md), [ADR-026](../decisions/ADR-026-platform-admin-and-mfa.md).

## Step-up (merchant token from `/auth/v1/token`)

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /platform/v1/mfa/enrollment` | — | `200 {secret, otpauthUri}` (no-store; shown once; replaces an unconfirmed secret) | `not_found` (not a platform admin), `mfa_already_enrolled` (409) |
| `POST /platform/v1/mfa/enrollment/confirmation` | `{code}` | `200 {recoveryCodes: [10 × "xxxxx-xxxxx"]}` (shown once) | `invalid_mfa_code` (401), `mfa_not_enrolled` (409), `rate_limited` |
| `POST /platform/v1/session` | `{code}` **or** `{recoveryCode}` | `200 {access_token, token_type: Bearer, expires_in: 900, amr}` | `invalid_mfa_code` (401: wrong, expired or already used), `mfa_not_enrolled`, `rate_limited` (429 after 5 wrong codes / 15 min), `invalid_parameter` (both or neither) |

## Platform API (platform token only)

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `GET /platform/v1/stores?q=&status=&limit=` | — | `200 {data: [{id, handle, name, status, currency, createdAt}]}` | — |
| `POST /platform/v1/stores/{storeId}/suspension` | `{reason}` | `200` store (SUSPENDED): storefront 404, developer API 403, admin API 403 `store_suspended`; audited in both logs | `store_status_conflict` (409), `store_not_found` |
| `POST /platform/v1/stores/{storeId}/reinstatement` | `{reason}` | `200` store (ACTIVE) | `store_status_conflict` |
| `GET /platform/v1/jobs` | — | `200 {jobsByStatus, unpublishedEvents, oldestReadyAgeSeconds, deadJobs}` | — |
| `POST /platform/v1/jobs/{jobId}/requeue` | — | `200 {jobId, requeued}` (audited) | — |
| `GET /platform/v1/audit?limit=` | — | `200 {data: [{id, adminUserId, action, targetType, targetId, details, clientIp, occurredAt}]}` | — |

Other instances see a suspension within 60 s (store directory cache). A merchant token on these endpoints gets 401.
