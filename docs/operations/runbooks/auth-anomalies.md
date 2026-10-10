# Runbook: Authentication anomalies

## Signals
| Signal | Meaning |
|---|---|
| `storeforge_auth_token_refreshes_total{outcome="reuse_detected"}` increasing | A rotated refresh token was presented again: token theft, **or** a client refreshing concurrently in two tabs |
| `storeforge_auth_logins_total{outcome=~"invalid_credentials|locked"}` spiking | Brute force / credential stuffing, or a broken client |
| `storeforge_ratelimit_decisions_total{limit="auth-ip",outcome="limited"}` spiking | One source hammering credential endpoints |
| `storeforge_ratelimit_rejected_total{limiter="auth-email"}` spiking | Many sources targeting few accounts (distributed attack) |
| `storeforge_notifications_emails_total{outcome="failed"}` > 0 | Users not receiving verification/reset emails |

## Diagnose
1. Reuse detected: find the WARN lines `Refresh token reuse detected` (fields `userId`, `familyId`, `requestId`). Many distinct users at once suggests a client bug (concurrent refresh), not theft. A single user, especially with different IPs in surrounding request logs, suggests theft.
2. Login failures: are they spread over many emails from few IPs (stuffing: per-IP limit should be firing), or many IPs against one email (per-email limit + lockout should be firing)?
3. Check that `X-Forwarded-For` is set by the proxy. If every request shows the proxy's IP, per-IP limits apply to everyone at once.

## Act
- Suspected theft for a user: trigger a password reset for them. This revokes all sessions and access tokens (security stamp). A dedicated "log out everywhere" admin action comes with the platform admin API (P14).
- Client bug: fix single-flight refresh in the SPA (ADR-008).
- Sustained attack: lower `AUTH_RATE_LIMIT_PER_IP` temporarily; block at the proxy/WAF.
- Email failures: check the provider/SMTP; affected users can request a new link.
