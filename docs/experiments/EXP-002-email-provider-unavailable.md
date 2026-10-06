# EXP-002: Email provider unavailable during registration

- **Date:** 2026-10-06 · **Phase:** P2 · **Setup:** app from the packaged jar on the host, Compose PostgreSQL + Mailpit; SMTP timeouts 2s connect / 5s read/write.

## Experiment
1. `docker compose stop mailpit`.
2. `POST /auth/v1/registrations` for a new email.
3. Inspect logs and `/actuator/prometheus`; `docker compose start mailpit`.

## Expected behavior
- Registration succeeds (the user row and token are committed) and returns the usual 202. An email outage must not block signups or leak an error.
- One WARN log with the request ID and the email type, but **no token and no address**.
- `storeforge_notifications_emails_total{outcome="failed"}` increments. Readiness is unaffected (mail health indicator disabled).

## Actual behavior
| Observation | Value |
|---|---|
| Response | `202` in 0.059s, identical body |
| Log | `WARN Identity email could not be delivered; the user can request a new one`, `emailType=VerifyEmail`, `requestId=c2e4…` |
| Metrics | `storeforge_notifications_emails_total{outcome="failed"} 1.0` |
| Readiness | UP throughout |

## What failed / why
The email is **lost**: it was attempted once, after commit, in the request thread. The user must click "resend". This is the documented P2 limitation (in-memory after-commit event, no retry).

Not covered by this run: an SMTP server that **accepts connections but hangs**. Connection refused fails in milliseconds; a hanging server would hold the registration request for up to the SMTP timeouts (≈ 2s + 5s). Rerun this with Toxiproxy latency in P17.

## How it was detected
WARN log + failed-email counter.

## How the system recovered
For new emails, as soon as SMTP was back. Lost emails require the user to resend.

## What we changed
Nothing yet. P9 moves email sending to the outbox + job queue: retries with backoff, nothing lost, nothing in the request path.
