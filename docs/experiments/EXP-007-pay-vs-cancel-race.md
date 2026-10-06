# EXP-007: "Mark as paid" vs. "cancel" on the same order, with and without the row lock

- **Date:** 2026-10-06 · **Phase:** P8 · **Setup:** `OrderLifecycleIT.concurrentPayAndCancelNeverBothSucceed`. 5 rounds; each round places a 1-unit order and fires `manual-payment` and `cancellation` simultaneously (latch). Outcome codes were printed per round for this experiment.

## Experiment
(a) Production code: `OrderRepository.lockByStoreIdAndId` (`SELECT … FOR UPDATE`). (b) Mutation: plain `findByStoreIdAndId` (no lock; only the JPA `@Version` check remains).

## Expected behavior
- (a) One 200, one 409 with a precise business code; stock consistent with the winner.
- (b) Still one winner. Both transactions read version 0, the first commits, and the second's flush fails the version check, rolling back its transaction **including its inventory side effect** (release or commit). The loser gets a generic error.

## Actual behavior
| Variant | Rounds | Loser's answer | Invariant (one winner, reserved = 0, on-hand = 10 − paid) |
|---|---|---|---|
| (a) lock | 4 × pay won, 1 × cancel won | `cancel_requires_refund` / `order_not_open` | ✅ |
| (b) no lock | 3 × pay won, 2 × cancel won | `concurrent_modification` | ✅ |

## What failed / why
Nothing, which is the instructive part: optimistic locking alone was sufficient here **because every side effect (stock commit/release) happened inside the same database transaction** and was rolled back with the losing write. The lock adds:
- specific, actionable errors (the loser waits, then sees the real new state);
- no wasted work (the loser doesn't run inventory statements just to roll them back).

## How it was detected
Invariant assertions after the race, plus the printed outcome codes.

## How the system recovered
N/A.

## What we changed
Kept the lock. Recorded the rule for P10 in [ADR-014](../decisions/ADR-014-orders-lifecycle.md): **when a side effect leaves the database (a payment provider call), "detect the conflict at commit" is too late. The money has already moved. Claim the row (or an IN_PROGRESS state) before calling out.**
