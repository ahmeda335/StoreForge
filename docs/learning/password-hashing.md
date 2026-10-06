# Password Hashing

**Problem.** Databases leak (backups, SQL injection, insiders). If passwords are stored in plaintext or with a fast hash (MD5, SHA-256), attackers recover most of them within hours: GPUs compute billions of SHA-256 guesses per second, and users reuse passwords across sites.

**Why we need it.** Merchants' accounts control stores and money. A leak of our `users` table must not hand out their passwords.

**Alternatives.** Plain SHA-256 (fast, so wrong); SHA-256 + salt (still fast); PBKDF2 (OK, but not memory-hard); BCrypt (good, battle-tested, 72-byte input limit); scrypt; **Argon2id** (memory-hard, winner of the Password Hashing Competition, OWASP's first choice).

**Decision.** Argon2id via Spring Security's `DelegatingPasswordEncoder` (default `{argon2}`, also accepts `{bcrypt}`).

**Mental model.**
- A password hash must be **slow and memory-hungry on purpose**. One login costs us ~50 ms, which is negligible. An attacker trying 10¹⁰ guesses pays that cost 10¹⁰ times, and needs memory per guess, which kills GPU parallelism.
- A random **salt** per password (stored inside the hash string) means identical passwords produce different hashes, so precomputed tables are useless and each account must be attacked separately.
- The hash string records the algorithm and parameters: `{argon2}$argon2id$v=19$m=16384,t=2,p=1$<salt>$<hash>`. `upgradeEncoding()` tells us when a stored hash uses outdated parameters. `LoginService` then re-hashes with the plaintext it just verified. This is the only moment we have it.
- Hashing is not encryption: there is no key and no way back. You verify by hashing the candidate and comparing.

**Project usage.** `SecurityConfiguration.passwordEncoder()`; `RegistrationService` (hash), `LoginService` (verify, rehash, dummy-hash timing parity), `PasswordResetService`.

**Failure cases.**
- Unbounded password length makes hashing a DoS lever, hence the 128-character maximum.
- **Timing leaks:** skipping the hash for unknown emails makes those responses measurably faster, which reveals which accounts exist. Fixed by verifying against a dummy hash.
- Parameters too high: login latency and CPU spikes under load. Measure in P15.
- Using a fast hash for passwords "because it's internal": never do it.

**Testing.** Registration/login integration tests run real Argon2. The hash format is asserted indirectly (login works, an upgrade path exists).

**Observability.** Login latency (`http_server_requests_seconds{uri="/auth/v1/token"}`) shows the hashing cost. CPU under login bursts.

**When NOT to use it.** For high-entropy random secrets (refresh tokens, API keys, email tokens) a slow hash is pointless: there is nothing to guess. We use SHA-256 there, which also allows lookup by hash.
