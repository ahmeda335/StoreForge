# Receiving Webhooks: Signatures, Replay, Duplicates, Ordering, Loss

**Problem.** The payment provider tells us "payment succeeded" by POSTing to a public URL. Anyone on the internet can POST to that URL. Real deliveries can be late, repeated, concurrent, out of order, or never sent at all.

**Why we need it.** Provider payment events (P10). The same problems, from the sending side, come back with merchant webhooks (P11).

**Alternatives.**
- **Polling only:** simple, but slow and expensive at scale. We keep it as the safety net (reconciliation).
- **Trusting the payload's ids without a signature:** forgeable.
- **IP allow-lists:** brittle, and not sufficient on their own.

**Decision.** Verify the signature, store and dedup, enqueue, and answer 200 fast. Apply idempotent state checks. Reconcile periodically for the deliveries that never come.

**Mental model.**
- **The signature is the authentication.**
  - It's an HMAC-SHA256 of `timestamp + "." + raw body` with a shared secret.
  - Verify the exact bytes received, before parsing: re-serialized JSON wouldn't match.
  - Compare in constant time (`MessageDigest.isEqual`), so the timing doesn't leak how many characters matched.
  - Accept any of several `v1` values (secret rotation).
- **The timestamp stops replay.** A captured, validly signed request replayed an hour later is refused (window ±5 min). The timestamp is inside the signed payload, so it can't be changed.
- **Dedup by the provider's event id:** a primary key `(provider, event_id)`. Providers retry until they get a 2xx, sometimes concurrently. The second insert is a no-op.
- **Answer fast, work later.** Store, enqueue a job, and answer 200 within milliseconds. Slow processing would make the provider time out and retry, causing more duplicates.
- **Dedup by event id is not enough.** The same *fact* can come under another event id (a resent event, or reconciliation racing the webhook). The applier itself must be idempotent: "capture" from an already-captured state is a no-op. [EXP-010](../experiments/EXP-010-payment-mutations.md) found this gap in our tests.
- **Ordering:** apply only transitions that are valid from the current state. Ignore stale ones (a "failed" after "succeeded").
- **Unknown objects:** log and count, but answer 2xx. Otherwise the provider retries forever.
- **Loss:** assume some webhooks never arrive. Reconcile: ask the provider about anything stale.

**Project usage.**
- `PaymentWebhookController` (raw `String` body, `/webhooks/**` security chain with no user auth);
- `StripeFormat.verify/parseEvent`;
- `WebhookIntake` (verify → `provider_events` insert + job in one transaction);
- `PaymentJobs.ProviderEventJob` → `PaymentService.apply`;
- `PaymentReconciliation`.

**Configuration.** `storeforge.payments.webhook-tolerance: 5m`; the secrets `STRIPE_WEBHOOK_SECRET` / `FAKE_PROVIDER_WEBHOOK_SECRET`; `provider-event-retention: 30d`, which must stay longer than the provider's retry window (Stripe: about 3 days).

**Failure cases.**
- Parsing before verifying;
- `String.equals` on signatures;
- no timestamp check;
- heavy work in the request;
- 4xx for unknown objects (endless retries);
- relying on event-id dedup alone;
- no reconciliation.

**Testing.**
- `StripeFormatTest`, which signs with an independent HMAC, so it doesn't test the code against itself;
- `CardPaymentIT`: tampered, replayed, forged and unsigned webhooks; 6 concurrent duplicates plus a late one plus a re-sent one under a new id;
- `PaymentRecoveryIT`: a lost webhook.

**Observability.** `storeforge_payments_webhooks_total{outcome=accepted|duplicate|ignored|rejected}` (a burst of `rejected` means a wrong secret or an attack); `storeforge_payments_events_unmatched_total`; WARN log "Rejected a payment webhook" with the reason.

**When NOT to use it.** When you control both sides and can use a queue. When the volume is so low that polling alone is fine.
