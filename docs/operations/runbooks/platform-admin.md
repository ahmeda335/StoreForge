# Runbook: Platform administrators (granting, revoking, lost authenticator)

Design: [ADR-026](../../decisions/ADR-026-platform-admin-and-mfa.md). API: [api/platform.md](../../api/platform.md).

## Symptoms
- A new staff member needs platform access.
- A staff member leaves, or their account may be compromised.
- An admin lost their phone (TOTP) and their recovery codes.
- `platform.session_started` entries with `amr` `recovery_code`, or at unusual times or addresses, in the platform audit log.

## Diagnose
- **Who is an admin:** `SELECT a.user_id, u.email, a.granted_at, a.note, a.totp_confirmed_at, cardinality(a.recovery_code_hashes) AS recovery_codes_left FROM platform_admins a JOIN users u ON u.id = a.user_id;` (any role can read).
- **What an admin did:** `GET /platform/v1/audit` (or the `platform_audit_log` table).

## Act
The application can't change `platform_admins`, by design. Connect as the **owner** role (`storeforge_owner`, the Flyway user):
- **Grant** (right before the person enrolls, see ADR-026 trade-offs):
  `INSERT INTO storeforge.platform_admins (user_id, note) SELECT id, 'ticket #123, approved by X' FROM storeforge.users WHERE email = 'person@storeforge.example';`
  Then the person signs in, enrolls (`POST /platform/v1/mfa/enrollment`), confirms with the first code, and stores the recovery codes safely.
- **Revoke:** `DELETE FROM storeforge.platform_admins WHERE user_id = '…';`. Effective on the next request: platform tokens are re-checked every time. If the account itself may be compromised, also reset the password ("log out everywhere" bumps the security stamp).
- **Lost authenticator and recovery codes:** verify the person out of band, then reset enrollment:
  `UPDATE storeforge.platform_admins SET totp_secret = NULL, totp_confirmed_at = NULL, totp_last_step = NULL, recovery_code_hashes = '{}' WHERE user_id = '…';`
  The person enrolls again.
- Every manual change gets a ticket reference in `note` or in the change log: these SQL actions are not in the platform audit log.
