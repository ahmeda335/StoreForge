# State Machines and Gapless Per-Tenant Counters

**Problem.** Orders move through states (placed, paid, fulfilled, cancelled), and each move has preconditions and side effects. Scattered `if (order.status == ...)` checks in services drift apart: someone ships an unpaid order or cancels a fulfilled one. Separately, merchants want readable order numbers (#1001, #1002, …), per store, without gaps or duplicates.

**Why we need it.** Orders (P8), payments and refunds (P10), webhook deliveries (P11), shipments (P13).

**Alternatives.** A single status enum with every combination (explosion); a state-machine library such as Spring Statemachine (heavy, hides the rules); **methods on the aggregate** that check and change state, plus database CHECKs. For numbering: UUIDs only (unfriendly), a sequence per tenant, a global sequence, or **a counter row per tenant**.

**Decision.** Orthogonal state dimensions with transition methods on `Order`. A `store_counters` row per store ([ADR-014](../decisions/ADR-014-orders-lifecycle.md)).

**Mental model.**
- **A transition method = guard + change + timestamp.** `markPaid()` throws `order_already_paid` if not PENDING. Callers can't set fields directly (no setters).
- **Orthogonal dimensions:** money (`financial_status`), goods (`fulfillment_status`) and overall (`status`) change independently. Their *combinations* still have rules (fulfilled ⇒ paid), so CHECK constraints catch combinations a buggy code path might produce.
- **Transitions need a lock** when they have side effects: two requests must not both pass the guard ([EXP-007](../experiments/EXP-007-pay-vs-cancel-race.md)).
- **Counter row:** `INSERT … ON CONFLICT (store_id) DO UPDATE SET n = n + 1 RETURNING n - 1`. The UPDATE row-locks the counter until the transaction ends: the next order waits (milliseconds) and gets the next number. If the order transaction rolls back, the increment rolls back too, so there are no gaps.
- **Why not sequences:** `nextval()` is **non-transactional** by design. It never rolls back, so concurrent transactions don't block each other; that makes sequences fast but leaves gaps. They are also database objects, so you can't create thousands of them per tenant without operational pain.
- **The cost of gapless:** order creation for one store is serialized on the counter row. That's fine at merchant scale, but it's a known hot spot (to measure in P15).

**Project usage.** `Order` (domain), `OrderStateMachineTest`, `OrderRecords.nextOrderNumber`, V7 CHECK constraints.

**Failure cases.** Adding a setter "just for this one case" (bypasses guards); forgetting the lock on a new transition; long transactions holding the counter row (every other order of that store waits).

**Testing.** Pure unit tests for every legal/illegal transition; `OrderPlacementIT` (12 concurrent orders → #1001..#1012, no gaps or duplicates); `OrderLifecycleIT` (illegal transitions over HTTP → 409 with specific codes; pay/cancel race).

**Observability.** `storeforge_orders_created_total`, `storeforge_orders_paid_total{method}`, `storeforge_orders_cancelled_total{reason}`; the order timeline (`order_events`) for per-order history.

**When NOT to use it.** Simple CRUD entities without lifecycle rules don't need a state machine. Identifiers that nobody reads don't need gapless counters: use UUIDs.
