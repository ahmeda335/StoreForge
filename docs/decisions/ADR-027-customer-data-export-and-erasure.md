# ADR-027: Customer data export and erasure through a per-module contract, in one tenant transaction

- **Status:** Accepted
- **Date:** 2026-10-09
- **Phase:** P14
- **Evidence:** [EXP-021](../experiments/EXP-021-platform-and-privacy-mutations.md); `CustomerPrivacyIT`

## Context
A shopper can ask a store for everything it holds about them (access) or to delete it (erasure). Their data is spread over five modules: customers, checkout, orders, discounts, notifications. Some of it must be kept: orders are accounting records.

## Problem
1. How does erasure stay complete when every module owns its own tables?
2. What is deleted, and what is kept?
3. How do we know nothing was missed?

## Options Considered
1. **Mechanism:** the customers module updates other modules' tables; an asynchronous "CustomerErased" event; **a synchronous contract (`PersonalData`) every module implements, run in one transaction**.
2. **Erasure:** delete rows; **replace personal fields with placeholders and keep accounting fields**.
3. **Verification:** tests per table; **search every text/JSON column of every table for the person's email, name and address**.

## Decision
1. `shared.privacy.PersonalData` (`section`, `checkErasable`, `export`, `erase`), implemented by orders, checkout, discounts and notifications. The customers module runs all of them inside one tenant transaction: a complete export, an all-or-nothing erasure.
2. **Erasure:**
   - **Customer:** email becomes `erased+<id>@erased.invalid`; names are removed; `erased_at` is set.
   - **Orders:** the same email placeholder; name and street become "Erased"; amounts, items, dates and country are kept.
   - **Checkouts:** email and address removed.
   - **Idempotency records:** stored responses that echo them are deleted.
   - **Discount redemptions:** the email key becomes `erased:<id>`.
   - **Emails:** the recipient is replaced; sent bodies are removed.

   Refused while the customer has OPEN orders (`customer_has_open_orders`). `customers:erase` is a separate, owner-level permission that no app scope maps to. Both export and erasure are audited without the data.
3. `CustomerPrivacyIT` searches the whole schema before (the email lived in 6 columns) and after (in none).

## Why We Chose It
- Each module keeps owning its data and knows its own retention needs; the contract makes "new module with personal data" a visible obligation.
- Synchronous and transactional: an erasure can't half-happen, and the merchant gets an answer, not a promise.
- Searching instead of listing found nothing missed today, and will catch the next column someone adds.

## Trade-offs
- A once-per-customer discount can be used again by an erased person.
- `order_events` (append-only) keep merchant-written notes; if a merchant typed personal data into a note, it stays.
- Data outside the database is not covered: logs (redacted by convention), the payment provider's records, webhook payloads already delivered to apps (they contain ids, not emails), backups (expire with retention).

## Consequences
- Any new table holding personal data needs a `PersonalData` implementation, or `CustomerPrivacyIT` fails.
- Erasure is irreversible and audited with the merchant's stated reason.
