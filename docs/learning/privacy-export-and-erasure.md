# Privacy: Data Export and Erasure

**Problem.** A person can ask a store what it holds about them, or to erase it. Their data is spread over many tables in several modules, and some records (orders) must be kept for accounting.

**Why we need it.** GDPR-style rights (access, erasure); a basic expectation of a commerce platform (P14).

**Alternatives.**
- **Delete rows:** breaks accounting and foreign keys.
- **Anonymize whole records:** loses business history.
- **Pseudonymize personal fields, keep the facts:** placeholders for who and where; amounts, items and dates stay.
- **Asynchronous fan-out:** no clear "done".

**Decision.** A `PersonalData` contract per module, run synchronously in one tenant transaction ([ADR-027](../decisions/ADR-027-customer-data-export-and-erasure.md)).

**Mental model.**
- **Personal data** is anything that identifies a person directly or indirectly: email, name, address. Their hashes count too (they can be guessed back).
- **Copies are the hard part:** stored responses (idempotency), queued emails, event payloads, logs, backups, third parties. List them by searching, not from memory.
- **Erasure ≠ deletion:** orders keep amounts, items, dates and the country (tax); the person behind them is gone.
- **Guard in-flight work:** refuse while an order may still ship, or the shop can't deliver.
- **Audit the act, not the data:** who erased, when, why; never the erased values.
- **Consequences** must be accepted explicitly: an erased customer can reuse a once-per-customer code.

**Project usage.**
- `shared.privacy.PersonalData`, implemented by `OrderPersonalData`, `CheckoutPersonalData`, `DiscountPersonalData`, `EmailPersonalData`;
- `CustomerPrivacyService`;
- `GET …/customers/{id}/export`, `POST …/customers/{id}/erasure`.

**Failure cases.**
- A copy nobody knew about (EXP-021: stored idempotency responses);
- partial erasure after an error (avoided with one transaction);
- erasure through a permission every staff member has;
- exporting someone else's data (tenant scoping).

**Testing.** `CustomerPrivacyIT` searches every text and JSON column of every table for the email, the name and the address, before (6 places) and after (0). It also tests open orders blocking erasure, the separate permission, and that a returning customer becomes a new customer.

**Observability.** Audit entries `customer.exported` / `customer.erased` with the reason.

**When NOT to use it.** Legal holds and statutory retention override erasure for the records they cover: keep those, and say so in the response.
