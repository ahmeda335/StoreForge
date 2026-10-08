# EXP-013: Do the webhook tests guard the defenses? (mutations)

- **Date:** 2026-10-08 · **Phase:** P11 · **Setup:** Testcontainers PostgreSQL, WireMock receiver; `WebhookDeliveryIT`, `JobQueueIT`.

## Experiment
Remove one defense at a time and run the test meant to guard it:

| # | Mutation | Guarding test |
|---|---|---|
| W1 | Follow redirects | `redirectsAreNeverFollowed` |
| W2 | Plain DNS in the HTTP client (SSRF check only at subscription time) | `aDestinationThatBecamePrivateIsNotContactedAtDeliveryTime` |
| W3 | No total deadline (only connect / per-read timeouts) | the dripping-receiver test |
| W4 | No per-type job limit | `slowJobsAreBulkheadedAndDoNotDelayOtherWork` |
| W5 | Previous secret not used during the rotation overlap | `secretRotationSignsWithBothSecretsDuringTheOverlap` |

## Expected behavior
Every mutation fails its test.

## Actual behavior
| # | Result |
|---|---|
| W1 | Failed (the redirect was followed, then blocked by the resolver: no 302 recorded) |
| W2 | Failed (the request went to 169.254.169.254: timeout and retry, instead of DEAD as "destination not allowed") |
| W3 | **Survived twice**, then failed after two test fixes (below) |
| W4 | Failed (more than 2 slow jobs ran at once) |
| W5 | Failed (one signature during the overlap; the old secret no longer verified) |

## What failed / why
W3 exposed two separate test bugs:

1. **The test looked at the wrong row.** It picked "the first RETRYING delivery". The earlier slow-response delivery was RETRYING too, so with W3 the dripping delivery quietly *succeeded* after 4 s and the test still passed.
2. **The test didn't model the attack.** After selecting the right row, it still passed. A diagnostic print showed `Read timed out` after 1274 ms with no status code: WireMock's "chunked dribble" delays the status line as well, so the ordinary per-read timeout fired. The attack the deadline exists for (status line at once, then a byte every few hundred milliseconds) was never exercised.

A third, unrelated issue surfaced in the full-suite run: the 4xx test passed alone but failed with the whole suite. It moved time forward, so other tests' leftover retries became due too, and the new bulkhead (4 deliveries per round) left its job for a later round.

## How it was detected
Mutation testing (a green test with the defense removed), then a diagnostic print of the recorded attempt. The full-suite failure came from running the whole suite twice before committing.

## How the system recovered
N/A. Mutations reverted.

## What we changed
- The dripping test now uses a **raw-socket server** that sends the status line immediately and then one chunk every 300 ms for 5 s, and it asserts on the newest delivery by recency. Verified that it fails under W3 (the delivery "succeeds" after 5 s).
- A separate `slowReceiversTimeOut` test covers plain slowness.
- Time-travel tests use `runDueAt(t)` (rounds until idle) instead of a single `runOnce(t)`.
- **Lesson:** a test for a defense must reproduce the *specific* threat, and a mutation run is how you find out whether it does.
