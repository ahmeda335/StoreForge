# 18 — Security Threat Analysis

Security is an architectural property: the **shape** of the code (explicit `StoreId` parameters, service-level authorization, RLS, hashed secrets, signed webhooks) prevents whole classes of bugs. A final "security review" cannot add that afterwards.

## Assets and trust boundaries

Assets: customer PII, merchant revenue (orders/payments), credentials (passwords, tokens, API keys, webhook secrets, provider keys), stock and price integrity, availability.
Trust boundaries: browser ↔ API, third-party app ↔ Developer API, provider ↔ our webhook endpoint, our dispatcher ↔ merchant URLs (outbound), app ↔ DB, operator ↔ platform admin.

## OWASP API Security Top 10 (2023) mapped

| Risk | Where it bites StoreForge | Control |
|---|---|---|
| API1 Broken Object Level Authorization (IDOR) | `GET /orders/{id}` with another store's id | Tenant-scoped repositories + RLS + isolation test suite; 404 |
| API2 Broken Authentication | Token forgery, credential stuffing, weak reset | Fixed-alg JWT validation, `aud`/`iss` checks, rotation + reuse detection, rate limits, uniform responses |
| API3 Broken Object Property Level Authorization | Mass assignment: `PATCH product {storeId: ...}` or `PATCH member {role: OWNER}` | Explicit request DTOs per use case (never bind entities); response DTOs exclude internal fields |
| API4 Unrestricted Resource Consumption | Huge `limit`, giant payloads, expensive search, image uploads | Max page size, request size limits, query timeouts (`statement_timeout`), rate limits, upload size caps |
| API5 Broken Function Level Authorization | Staff calling owner-only endpoints; customers calling admin API | Per-API security chains + permission checks in services + tests per role |
| API6 Unrestricted Access to Sensitive Business Flows | Card testing via checkout; coupon brute force; inventory hoarding via reservations | Rate limits per IP/cart/store, reservation only at completion, coupon attempt limits |
| API7 SSRF | Webhook URLs, image import from URL | Resolve DNS, block private/link-local/loopback/metadata ranges, no redirects, egress proxy in prod |
| API8 Security Misconfiguration | Actuator exposed, verbose errors, permissive CORS | Actuator on a separate management port, not public; problem details without internals; CORS allow list per API |
| API9 Improper Inventory Management | Old API versions forgotten, undocumented endpoints | Versions catalogued, deprecation policy, OpenAPI generated from code |
| API10 Unsafe Consumption of APIs | Trusting provider webhooks/responses | Signature verification, schema validation, timeouts, treat as untrusted |

## Specific threats

| Threat | Control |
|---|---|
| SQL injection | Parameterized queries only (JPA/`JdbcClient` named params); sort/filter via allow lists; no string-concatenated SQL. ArchUnit/grep check in CI |
| XSS | API returns JSON only; Angular escapes by default; merchant-provided HTML (product descriptions) sanitized server-side with an allow list (OWASP Java HTML Sanitizer); CSP on frontends |
| CSRF | Bearer tokens in headers for APIs (not CSRF-able); refresh cookie `SameSite=Strict` + Origin check + custom header |
| Credential attacks | Argon2id, rate limits per IP+account, breached-password check (k-anonymity HIBP) later, MFA later |
| Secret management | Never in Git. `.env` locally (gitignored; `.env.example` committed). Prod: platform secret store (e.g. Docker secrets / cloud secret manager / Vault). Gitleaks in CI |
| Encryption | TLS everywhere external (and to managed DB). At rest: disk encryption by the platform; application-level AES-GCM for webhook secrets and provider tokens (key from secret store, `key_id` stored for rotation) |
| Password storage | Argon2id via `DelegatingPasswordEncoder` (upgradeable) |
| Sensitive data logging | Redaction conventions + log-scan test (see observability) |
| Webhook signature verification (inbound, provider) | HMAC over raw body, constant-time compare, timestamp tolerance |
| Replay attacks | Timestamp tolerance + event-id dedup (inbound); single-use tokens (reset/verify); refresh rotation with reuse detection; idempotency keys |
| Dependency vulnerabilities | Dependabot + OWASP Dependency-Check or Trivy (fs + image) in CI; fail on High/Critical with a documented allow list; minimal base images |
| Container security | Non-root user, read-only FS where possible, distroless/JRE base, image scanning |
| Platform admin abuse | Separate auth audience, MFA, all actions audited, impersonation read-only + visible to merchant |
| Data retention / privacy | Customer data export & deletion endpoints (GDPR-style) as an advanced feature; PII minimization in logs/events/webhooks (scoped by `read_customers`) |

## Security in every phase

Each roadmap phase lists its security tasks. The checklist for any feature: authN? authZ (permission + tenant)? input validation? rate limit? sensitive data in logs/events? secrets? audit entry? abuse scenario?
