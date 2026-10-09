# EXP-021: Platform admin and customer erasure: do the tests guard the rules? (mutations, and what they exposed)

- **Date:** 2026-10-09 · **Phase:** P14 · **Setup:** Testcontainers PostgreSQL + Redis + SeaweedFS; `PlatformAdminIT`, `CustomerPrivacyIT`, `PropertyLevelAuthorizationIT`, `FunctionLevelAuthorizationIT`, `HttpEdgeIT`.

## Experiment
Remove one rule at a time and run the test meant to guard it:

| # | Mutation |
|---|---|
| A1 | TOTP replay check removed (`totp_last_step < :step`) |
| A2 | Platform token no longer re-checks "still an enrolled admin" |
| P1 | Erasure keeps idempotency responses (which echo email and address) |
| U1 | `fail-on-unknown-properties: false` (unknown JSON fields ignored) |
| F1 | One permission check removed (`ShippingRateService.create`) |

## Expected behavior
Every mutation fails its test.

## Actual behavior

| # | Result |
|---|---|
| A1 | Failed (the same code opened a second session) |
| A2 | Failed (a revoked admin's token kept working) |
| P1 | Failed: `email left in: ["idempotency_keys.response_body"]` |
| U1 | Failed in 3 tests (smuggled fields accepted silently) |
| F1 | Failed: a member with no permissions → `POST …/shipping-rates → 201` |

Writing the tests also exposed four mistakes in my own assumptions:

| Finding | Kind |
|---|---|
| The MFA attempt limit counted *successful* logins too: 6 legitimate steps hit 429 | Product bug |
| I assumed a suspended store's merchant could still read the admin API; P3 had decided 403 `store_suspended` | Wrong assumption |
| Over-posting tests expected unknown fields to be ignored; P1 had chosen to reject them (400) | Wrong assumption |
| A permissionless member got 422 on writes (Bean Validation runs before the service's permission check) | Test design |

## What failed / why
- **The MFA limit** should count wrong codes only. A token is now taken before checking (so parallel guesses can't slip past) and refunded unless the code was wrong.
- **The two wrong assumptions:** earlier decisions were stricter than I remembered. The tests now assert them, and the ADRs say so.
- **The 422s:** not a leak (the request shape is public, nothing is read or written), but the test has to reach the permission check. Valid bodies are now shared in `support/SampleRequests`.
- **The erasure search:** the email lived in 6 columns. One of them (stored idempotency responses) is easy to forget, and searching the schema is what guards it.

## How it was detected
Mutation runs, and first runs of the new tests.

## How the system recovered
N/A.

## What we changed
- Refund-unless-wrong for the MFA limit.
- Tests asserting the P1/P3 behaviour explicitly.
- `SampleRequests` shared by `TenantIsolationIT` and `FunctionLevelAuthorizationIT`.
- **Lesson:** a security test should search or enumerate (every column, every endpoint), not list what the author remembered.
