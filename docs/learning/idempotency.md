# Idempotency

**Problem.** Networks fail *after* the server did the work: the client sees a timeout and doesn't know whether the purchase happened. Clients retry, users double-click, proxies resend. A non-idempotent endpoint turns every such retry into a second purchase, reservation or charge.

**Why we need it.** Checkout completion (P7), payments, captures and refunds (P10), webhook and job consumers (P9/P11).

**Alternatives.** Hope (no); "disable the button" (helps humans, not networks); natural idempotency via state machines (prevents double *effects* but gives retries a different *answer*: [EXP-006](../experiments/EXP-006-duplicate-checkout-completion.md)); **client-chosen idempotency keys with stored results** (Stripe's model).

**Decision.** A required `Idempotency-Key` on completion. The result is stored per (store, operation, key) and replayed for retries ([ADR-013](../decisions/ADR-013-checkout-and-idempotency.md)).

**Mental model.**
- **Idempotent** (HTTP sense): doing it N times has the same effect as once. GET/PUT/DELETE are idempotent by definition, POST is not. An idempotency key makes a specific POST behave idempotently.
- Two properties: **effect at most once** (no double reservation) and **same response every time** (the client can trust a retry). We need both.
- **The key belongs to the client.** It generates a UUID per *intended operation* (one per "Pay" click) and reuses it for every retry of that operation.
- **Fingerprint:** the same key with a different request is a client bug, so we answer 422 instead of silently replaying a response that doesn't match. Include the *caller's identity* in the fingerprint, or a leaked key would let someone else read the stored response.
- **Concurrency:** a duplicate that arrives while the first is still running must not run the work too. Here, the first request's uncommitted `INSERT` of the key row makes the duplicate's `INSERT` **wait** on the primary-key index. When the first commits, the duplicate reads the stored response. If the first rolls back, the key row disappears and the duplicate runs the work itself.
- **What gets stored:** only successful results here. Failures re-execute on retry (our failures are deterministic validations, and conditions can change, e.g. restocked items).
- **Limits:** this single-transaction model holds the transaction for the duration of the work. With slow external calls (a payment provider), use an explicit `IN_PROGRESS` row committed first, return 409 to duplicates while in flight, and use a lease for crashed requests (P10).

**Project usage.** `shared.idempotency.Idempotency`; `StorefrontCheckoutController.complete`; table `idempotency_keys` (RLS).

**Failure cases.** Clients generating a new key per retry (no protection: document the contract); keys never expiring (table growth, cleanup in P9); storing responses that contain secrets (the cart token is *not* in the completion response, by design).

**Testing.** `CheckoutIdempotencyIT`: 20 concurrent same-key requests → one execution, identical bodies; different keys → one completion; key reuse with another payload or shopper → 422 with no PII; failed attempts not stored. Mutation-tested.

**Observability.** `storeforge_idempotency_replays_total{scope}`: a sudden rise means clients are retrying a lot (timeouts? a slow dependency?). `Idempotent-Replayed` response header.

**When NOT to use it.** Naturally idempotent operations (PUT a full resource, DELETE) and reads. And it never replaces database constraints and state machines: those still guard the effect.
