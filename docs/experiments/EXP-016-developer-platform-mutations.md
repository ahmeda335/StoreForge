# EXP-016: Do the developer platform tests guard its rules? (mutations, and what they exposed)

- **Date:** 2026-10-08 · **Phase:** P12 · **Setup:** Testcontainers PostgreSQL + Redis; `DeveloperApiIT`, `RateLimitIT`, `OAuthInstallIT`.

## Experiment
Remove one rule at a time and run the test meant to guard it:

| # | Mutation |
|---|---|
| D1 | Interceptor's store binding removed (URL store ≠ credential store allowed) |
| D2 | D1 + `StoreAccess`'s API-client store check removed |
| D3 | Interceptor's scope check removed |
| D4 | No anti-escalation when creating API keys |
| O1 | No scope-permission re-check at OAuth token issuance |
| O1b | No `apps:manage` re-check at token issuance |
| O2 | Pending authorizations kept after issuance (plaintext token stored) |
| O3 | PKCE not required |
| O4 | OAuth login with a random client IP (bypassing the per-IP limit only) |

## Expected behavior
Every mutation fails its test. D1 was expected to *survive*, thanks to the service-layer store check.

## Actual behavior

| # | Result |
|---|---|
| D1 | **Failed**, but not the way I predicted: **200** instead of 404, with the *key's own store's* orders |
| D2, D3, D4 | Failed |
| O1 | **Survived**, then failed after adding a test (below) |
| O1b, O2, O3 | Failed |
| O4 | Survived: a bad mutation (below) |

Separately, writing the tests exposed a product bug and two flaky tests:

| Finding | Kind |
|---|---|
| Usage counted 40 of 45 requests: **rejected requests were never counted**, so `rate_limited` would always read 0 | Product bug |
| Rate-limit tests failed intermittently | Test bugs |

## What failed / why
- **D1:** developer controllers pass the *credential's* store to services, never the URL's. So the service-layer check compares the credential's store with itself and passes. Without the interceptor's binding, a request to another store's URL answers with **the caller's own data**. That's wrong (the URL lies) but **not a cross-tenant leak**: no store's data is reachable through a credential of another. The binding check is what produces the correct 404, and the test guards it.
- **O1:** the forged-consent test used a staff user without `apps:manage`. The first re-check refused them, so the scope re-check was never reached.
- **O4:** randomizing the IP only bypasses the per-IP limit. The per-email limit and the lockout (in identity) still applied, and the test rightly passed. A meaningful version would replace `PasswordAuthenticator` with a plain password check; not done.
- **Usage bug:** Spring calls `afterCompletion` only when `preHandle` returned true. Every request the interceptor rejected (429, 403, 404) skipped usage counting.
- **Flaky rate-limit tests:**
  - fixed request counts assumed buckets don't refill during the test (they refill at 2/s and 5/s);
  - the frozen-Redis test left Redis busy with buffered commands for the next test.

  One failing state was committed by mistake: the commit command didn't stop on the test failure (6401c0f). It was fixed in the next commit.

## How it was detected
Mutation runs (green tests with a rule removed), a usage assertion that read 40 instead of 45, and repeated test runs.

## How the system recovered
N/A.

## What we changed
- **O1:** added `anAppCannotBeGrantedMoreThanTheInstallingMerchantHolds` (a merchant with `apps:manage` + `orders:read` forging consent for `write_inventory` → `invalid_grant`). O1 now fails it.
- **O2:** added a direct assertion that `oauth2_authorization` holds no access token after issuance.
- **Usage bug:** usage is counted in a `UsageFilter` inside the developer security chain, which sees every final status. Covered by `aBurstBeyondTheBucketGets429…`.
- **Flaky tests:** drain buckets *until refused*; assert ranges where refill matters; start every rate-limit test with a responsive Redis and a closed breaker; assert the breaker's recovery explicitly. Five consecutive green runs.
- **Lesson:** a test proves a rule only if it reaches it. Mutation runs find the tests that don't.
