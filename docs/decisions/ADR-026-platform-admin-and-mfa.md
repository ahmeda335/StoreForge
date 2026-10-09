# ADR-026: Platform administration behind password + mandatory TOTP, its own token audience, audited everywhere

- **Status:** Accepted
- **Date:** 2026-10-09
- **Phase:** P14
- **Evidence:** [EXP-021](../experiments/EXP-021-platform-and-privacy-mutations.md); `PlatformAdminIT`, `TotpTest`, `FunctionLevelAuthorizationIT`

## Context
StoreForge staff need to act on any store: suspend one that is used for card testing, requeue dead jobs, read what other staff did. Until P14 the only operator surface was the unauthenticated actuator on the private port. A platform account can reach every tenant, so it is the most valuable credential in the system.

## Problem
1. Who is a platform admin, and how does someone become one?
2. How strong must authentication be, and how is a password-only session kept away from the platform API?
3. How do merchants learn what the platform did to their store?

## Options Considered
1. **Who:** a role flag on users granted through the API; **a separate table the application can't write to**.
2. **Authentication:** password only; SMS codes; WebAuthn; **TOTP (RFC 6238) + recovery codes, step-up to a short-lived token with its own audience**.
3. **Visibility:** platform audit only; **platform audit + an ADMIN entry in the store's own audit log**.

## Decision
1. **`platform_admins`** with `INSERT`/`DELETE` revoked from the application role. Operators grant and revoke with the owner role ([runbook](../operations/runbooks/platform-admin.md)). Even SQL injection in the app can't create an admin (tested).
2. **Step-up:**
   - a normal password login gives a merchant token;
   - `POST /platform/v1/mfa/enrollment` (shown once), then `.../confirmation` with the first code, which also returns 10 one-time recovery codes (stored as SHA-256);
   - `POST /platform/v1/session` with a TOTP or recovery code returns a **platform token**: audience `platform`, client id `storeforge-platform`, `amr`, 15 minutes, no refresh;
   - each 30-second step is accepted once (conditional update on `totp_last_step`); 5 **wrong** codes per 15 minutes per admin (a token is taken up front and refunded unless the code was wrong);
   - the TOTP secret is sealed with AES-GCM (own key, user id as associated data);
   - every request re-checks: account active, security stamp, still an enrolled admin. Deleting the row cuts a token off immediately.
3. Two security chains: the step-up endpoints accept merchant tokens; everything else under `/platform/**` accepts only platform tokens. Neither token works on the other API (each decoder pins its audience).
4. **Audit:** `platform_audit_log` (append-only for the app) records sessions, enrollments, suspensions and requeues with the client IP. Store changes also go into the store's audit log as actor `ADMIN`, with the reason.

## Why We Chose It
- **TOTP** works with any authenticator app, needs no SMS provider (SIM swapping) and no browser integration. WebAuthn is stronger (phishing-resistant) and is the next step once the admin UI exists.
- **A separate audience** makes "password session reaches staff functions" structurally impossible instead of a check someone might forget.
- **Write-protection at the database role** removes the most dangerous endpoint (creating admins) entirely.

## Trade-offs
- TOTP can be phished in real time; recovery codes are bearer secrets the admin must store safely.
- Enrollment needs only the password session: an attacker with an admin's password *before* first enrollment could enroll. Operators grant admin rights only right before the person enrolls.
- No impersonation ("view as merchant") yet.

## Consequences
- Suspension keeps the P3 rule: the merchant's admin API answers `403 store_suspended`, the storefront 404, the developer API 403. The merchant sees the ADMIN entries after reinstatement.
- Losing the phone and every recovery code needs an operator reset ([runbook](../operations/runbooks/platform-admin.md)).
