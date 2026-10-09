# TOTP and Step-Up Authentication

**Problem.** A password is one secret; once phished or reused, the account is gone. For accounts that can act on every tenant, one factor isn't enough.

**Why we need it.** Platform administrators (P14).

**Alternatives.**
- **SMS codes:** SIM swapping, delivery problems.
- **TOTP:** an app computes codes from a shared secret and the time.
- **WebAuthn/passkeys:** phishing-resistant, but needs browser integration.
- **Push approval:** prompt fatigue.

**Decision.** TOTP (RFC 6238), mandatory, plus one-time recovery codes; step-up to a separate token audience ([ADR-026](../decisions/ADR-026-platform-admin-and-mfa.md)).

**Mental model.**
- **HOTP (RFC 4226):** `HMAC-SHA1(secret, counter)`, dynamically truncated to 6 digits. **TOTP:** counter = `floor(unix time / 30)`. Phone and server compute the same thing independently.
- **Window:** accept ±1 step for clock drift, so ~3 codes are valid at any time. That's 1 in ~333,000 per guess, which is why **wrong attempts must be limited**.
- **Replay:** a code stays valid for up to 90 s. Remember the last accepted step and accept only later ones (conditional update).
- **Secret at rest:** it must be readable to verify, so it's encrypted (AES-GCM), not hashed. Recovery codes are only compared, so they're hashed (high entropy: SHA-256 is enough).
- **Step-up:** the factor isn't checked on every request. It's exchanged once for a short-lived token with its own audience; the merchant token can't reach the platform API at all.
- **Revocation:** re-check "still an admin" per request, so removing someone takes effect at once, not at token expiry.

**Project usage.** `Totp`, `Base32`, `PlatformMfaService`, `PlatformTokens`, `PlatformTokenConverter`, the `platformStepUp` / `platformApi` chains.

**Failure cases.**
- Unlimited guesses;
- no replay check;
- counting successes against the limit (EXP-021);
- plaintext secrets;
- one audience for both kinds of token;
- enrollment that an attacker with the password can complete first.

**Testing.**
- RFC 6238 test vectors (`TotpTest`).
- An independent authenticator in tests (`TestAuthenticator`), so a shared bug can't make both sides agree.
- Replay, older-step, brute-force, recovery-once and revocation tests; mutation-checked.

**Observability.** `platform_audit_log` (`platform.session_started` with `amr`); a WARN log when a recovery code is used.

**When NOT to use it.** Where phishing resistance is required (use WebAuthn), or for machine accounts (use credentials with scopes).
