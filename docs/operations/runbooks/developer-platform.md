# Runbook: Developer platform (API keys, apps, Redis)

## Symptoms
- `/actuator/health` shows `DEGRADED` with redis `DEGRADED`: Redis unreachable. The developer API is serving with per-instance limits, and usage isn't recorded.
- `resilience4j_circuitbreaker_state{name="redis",state="open"} 1`; `storeforge_ratelimit_decisions_total{backend="local"}` rising.
- An integrator reports 429s: `rate_limited` in their key's usage, `X-RateLimit-Remaining: 0`.
- A token leaked (found in a repository, logs, or a support ticket): `sfk_…` or `sfat_…`.
- An app install fails at the token step with `invalid_grant`.

## Diagnose
1. **Redis:** `redis-cli -u $REDIS_URL ping`, and the app logs "Redis circuit breaker changed state". Limits are N× looser while it's down; nothing else breaks.
2. **429 complaints:** `GET /admin/v1/stores/{id}/api-keys/{keyId}/usage?hours=24`. Is it a burst from one key (the per-credential bucket, 40 / 2 per s) or many keys (the store bucket, 100 / 5 per s)? Clients must honour `Retry-After` (see StockSync).
3. **`invalid_grant` at the token endpoint:**
   - The code was used twice, or is older than 5 minutes.
   - The `code_verifier` doesn't match.
   - The approving merchant lacks `apps:manage` or a scope's permission (re-checked at issuance). Check their role.
4. **Who used a credential:** the audit log's `actorType` `API_KEY` / `APP` with `actorId` = the credential id; `last_used_at` on the key.

## Act
- **Leaked API key:** `DELETE /admin/v1/stores/{id}/api-keys/{keyId}` (effective on the next request), then create a new key. Review the audit log for the key's actions.
- **Leaked app token:** uninstall (`DELETE …/app-installations/{id}`) and reinstall. The app's webhook subscriptions are deleted with it; the app recreates them.
- **Redis down:** restore it. No action is needed on the app: the breaker closes by itself, and buckets start full (a brief burst allowance).
- **Never** relax `WEBHOOKS_ALLOWED_PRIVATE_CIDRS` or `WEBHOOKS_REQUIRE_HTTPS` in production to "make an app work".

## Notes
- Tokens are stored only as SHA-256. Nobody can look one up: re-issue instead.
- OAuth browser sessions are in memory per instance: install flows need sticky sessions with more than one instance (open item, ADR-020).
