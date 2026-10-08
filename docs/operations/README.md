# Operations

Runbooks describe how to diagnose and act on a symptom. Every alert (P15/P16) links to one.

| Runbook | Symptom |
|---|---|
| [database-unavailable](runbooks/database-unavailable.md) | Readiness DOWN with `db` component DOWN; 503s; Hikari timeouts |
| [object-storage-and-search](runbooks/object-storage-and-search.md) | Image uploads failing, storage errors, DEAD deletion jobs, slow storefront search |
| [developer-platform](runbooks/developer-platform.md) | Redis down / DEGRADED, 429 complaints, leaked keys or app tokens, failed installs |
| [webhooks](runbooks/webhooks.md) | Failing or paused merchant endpoints, blocked destinations, replays, secret problems |
| [payments](runbooks/payments.md) | Provider outage / open circuit, rejected or lost webhooks, stuck refunds, compensations |
| [dead-jobs](runbooks/dead-jobs.md) | DEAD jobs, growing job age, outbox not draining |
| [auth-anomalies](runbooks/auth-anomalies.md) | Refresh-token reuse detected; spikes in failed logins or rate limiting; email delivery failures |
