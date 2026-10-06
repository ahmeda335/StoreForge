# Auth API: `/auth/v1` (implemented, P2)

All bodies are JSON. Errors are Problem Details ([conventions](conventions.md)). Unknown JSON properties are rejected with `400 bad_request`. Try them with [`http/auth.http`](../../http/auth.http).

| Method & path | Body | Success | Error codes |
|---|---|---|---|
| `POST /auth/v1/registrations` | `{email, password}` | `202` (same response whether or not the email exists) | `validation_failed` (422), `invalid_email`, `weak_password`, `rate_limited` (429) |
| `POST /auth/v1/email-verifications` | `{email}`: resend link | `202` always | `rate_limited` |
| `POST /auth/v1/email-verifications/confirmation` | `{token}` | `204` | `invalid_or_expired_token` (422) |
| `POST /auth/v1/token` | `{email, password}`: login | `200 {access_token, token_type: "Bearer", expires_in: 900}` + `Set-Cookie: sf_refresh` | `invalid_credentials` (401: wrong password, unknown email, locked and disabled all look the same), `email_not_verified` (403), `rate_limited` |
| `POST /auth/v1/token/refresh` | none; cookie `sf_refresh`, header `X-Requested-With: XMLHttpRequest`, `Origin` must be allowed if present | `200` new access token + rotated cookie | `invalid_refresh_token` (401), `cross_site_request_rejected` (403) |
| `POST /auth/v1/token/revocation` | none; same headers as refresh: logout | `204` + cookie cleared | `cross_site_request_rejected` |
| `POST /auth/v1/password-resets` | `{email}` | `202` always | `rate_limited` |
| `POST /auth/v1/password-resets/confirmation` | `{token, newPassword}` | `204`; all sessions and access tokens revoked | `invalid_or_expired_token`, `weak_password` |
| `GET /auth/v1/jwks.json` | — | `200` public JWK Set (cache 5 min) | — |

Protected endpoint (merchant admin API, needs `Authorization: Bearer <access_token>`):

| Method & path | Body | Success | Error codes |
|---|---|---|---|
| `GET /admin/v1/me` | — | `200 {id, email, emailVerified, createdAt}` | `unauthenticated` (401 + `WWW-Authenticate: Bearer ...`) |

Token responses carry `Cache-Control: no-store`. Email links point to the SPA (`APP_BASE_URL`) with the token in the fragment: `/verify-email#token=…`, `/reset-password#token=…`.

## Limits

| Limit | Default | Config |
|---|---|---|
| Per client IP, all credential endpoints | 30/min | `AUTH_RATE_LIMIT_PER_IP` |
| Per target email and action | 10/min | `AUTH_RATE_LIMIT_PER_EMAIL` |
| Lockout | after 5 consecutive failures: 1 min, doubling, max 1 h | `storeforge.auth.lockout.*` |

The client IP is `request.getRemoteAddr()`, which honours `X-Forwarded-For` (`server.forward-headers-strategy=framework`). In production the reverse proxy **must overwrite** `X-Forwarded-For`; otherwise clients can spoof their IP and dodge per-IP limits (P16).
