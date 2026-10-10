# Rate Limiting, Account Lockout and Enumeration Resistance

**Problem.** Credential endpoints are attacked constantly: brute force (many passwords, one account), credential stuffing (leaked email/password pairs, many accounts), and **enumeration** (finding which emails have accounts, from different error messages or response times).

**Why we need it.** Every public SaaS login gets this traffic from day one.

**Alternatives.** CAPTCHA (friction, later as step-up); WAF/edge rate limiting (complementary, P16); permanent lockout (lets attackers lock out victims, so no).

**Decision.** Three layers: per-IP token bucket, per-target-email token bucket, and temporary exponential account lockout. Plus uniform responses and timing on all credential flows.

**Mental model.**
- **Token bucket:** capacity *C*, refill *C* per minute. Bursts up to *C* are allowed, and the sustained rate is bounded. No window-boundary double burst (unlike fixed windows).
- **Per IP** stops one machine; **per email** stops a botnet focused on one account; **lockout** is a slower, persistent layer stored in the DB (survives restarts, shared across instances).
- **Enumeration resistance:** registration, password reset and resend always answer `202` with the same body. Login answers `invalid_credentials` for unknown email, wrong password, locked and disabled. The *real* reason is recorded in metrics, not in responses. Timing parity: unknown emails are checked against a dummy Argon2 hash.
- Lockout counters use **atomic SQL** (`count = count + 1`): a read-modify-write would lose increments exactly under the concurrent attack it should stop.

**Project usage.** `DistributedRateLimiter` (Redis, shared by all instances since P16; it was per-instance memory before, EXP-024), `AuthRateLimits`, `UserLoginCounters`, `LoginService`, `RegistrationService`.

**Configuration.** `AUTH_RATE_LIMIT_PER_IP` (30/min), `AUTH_RATE_LIMIT_PER_EMAIL` (10/min), `storeforge.auth.lockout.*` (5 failures → 1 min doubling to 1 h).

**Failure cases.**
- In-memory buckets: N instances → N× the limit; a restart resets them (P12 moves them to Redis).
- Behind a proxy, all clients share the proxy's IP unless `X-Forwarded-For` is trusted. If it is trusted from *clients*, they can spoof it. The proxy must overwrite it.
- Lockout as a DoS: attackers can still lock a victim for up to an hour. Password reset clears the lock.
- Email is still enumerable via the "account exists" email (but only to the inbox owner, which is fine).

**Testing.** `LoginIT` (lockout, 429 with `Retry-After`, uniform errors); `RegistrationIT` (identical responses).

**Observability.** `storeforge_ratelimit_decisions_total{limit="auth-ip"|"auth-email", outcome="limited"}` (shared via Redis since P16, EXP-024), `storeforge_auth_logins_total{outcome}`.

**When NOT to use it.** Don't apply strict per-IP limits to endpoints legitimately used by shared IPs at scale (corporate NAT, mobile carriers) without measuring. Storefront limits (P12) are tuned separately.
