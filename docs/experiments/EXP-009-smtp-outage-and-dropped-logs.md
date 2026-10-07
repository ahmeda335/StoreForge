# EXP-009: SMTP outage during registration (EXP-002 revisited), and the log lines that never were

- **Date:** 2026-10-07 · **Phase:** P9 · **Setup:** app from the jar (job poll 500 ms), Compose PostgreSQL + Mailpit.

## Experiment
1. Full shopper flow with real SMTP: registration email, order, confirmation email, payment, customer stats.
2. `docker compose stop mailpit` → register a new merchant → inspect `/actuator/jobs` → `docker compose start mailpit` → wait for the email in Mailpit, without touching anything.

## Expected behavior
1. Emails arrive within about a second (asynchronous job), and the order confirmation shows the right line. Stats are updated by the `OrderPaid` consumer.
2. Registration still answers 202 quickly. The email job fails, logs a WARN with `jobId`, and retries with backoff. Once SMTP is back, it's delivered **without manual action**. Compared with EXP-002 (P2), where the email was lost.

## Actual behavior
| Step | Observed |
|---|---|
| 1 | Verification email after **0.5 s**; order `#1001` → "Order #1001 confirmed: Demo Shop" with "2 × Desk Lamp 49.00 USD"; after payment: customer **1 order, 4900 minor**; queue: 5 DONE, 0 DEAD, 0 unpublished |
| 2 | Register `202` in 0.29 s; queue while down: 1 READY; **delivered 10 s after registration, automatically**; metrics `email.send{outcome=retry} 1`, `{outcome=done} 1` |
| 2 (log) | **The WARN "Job failed; will retry" line was missing from the log** |

## What failed / why
The worker put `jobId`/`jobType` in the MDC **and** added them as key-values on the log event. Spring Boot's structured (JSON) encoder refuses duplicate keys (`IllegalStateException: Duplicate nested pairs added under 'jobId'`). Logback then **dropped the whole event**, printing only an internal status message. The same applied to the ERROR "moved to DEAD" line. Failures of async work were invisible in the logs, while the counters still showed them.

The tests were green because no test asserted that the line was actually written.

## How it was detected
Manual verification: the metric said "1 retry", but no WARN line existed. Reading the raw stdout showed Logback's status error and the stack trace.

## How the system recovered
The email itself was delivered (the queue works). The *observability* failure needed a code fix.

## What we changed
- Removed the duplicate key-values; the MDC already provides `jobId`/`jobType` (`JobWorker.fail`).
- Regression assertions in `JobQueueIT`: the retry WARN (with `"jobId":"<id>"`) and the DEAD ERROR lines must appear in the output. Verified that the assertion fails when the bug is re-introduced.
- Lesson recorded in [learning/postgres-job-queue.md](../learning/postgres-job-queue.md): **test that important log lines are emitted**, and never add a key-value that's also in the MDC with the JSON encoder.
