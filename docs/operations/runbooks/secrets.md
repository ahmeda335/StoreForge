# Runbook: Secrets and deployment security settings

## Symptoms
- Startup fails: `Refusing to start with development secrets (public, from application.yml) for: [...]`.
- Startup fails: `storeforge.auth.signing-jwk is required (ephemeral keys are disabled)`.
- CI job `secrets` fails (gitleaks), or a secret is reported leaked (repository, log, ticket).
- Storefront limits or audit IPs show the load balancer's address for every client.

## Diagnose
1. **Development secrets refused:** the listed properties still hold the values committed in `application.yml`. That's intended outside local development (`ALLOW_DEVELOPMENT_SECRETS=false`).
2. **gitleaks failure:** look at the report (rule, file, line; values redacted). It's either a real secret, or a value that needs a justified, line-based exception in `.gitleaks.toml`.
3. **Every client has the same address:** the proxy in front is not in `TRUSTED_PROXIES`, so X-Forwarded-For is ignored.

## Act
- **Set real values** (secret store or environment, never Git):

  | Variable | What | How to generate |
  |---|---|---|
  | `WEBHOOK_ENCRYPTION_KEY` | Seals webhook signing secrets | `openssl rand -base64 32` |
  | `PLATFORM_MFA_ENCRYPTION_KEY` | Seals admins' TOTP secrets | `openssl rand -base64 32` (its own key) |
  | `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | Object storage | from the storage provider (scoped to the bucket) |
  | `FAKE_PROVIDER_WEBHOOK_SECRET` | Only if the fake provider runs | random; production uses `PAYMENTS_PROVIDER=stripe` |
  | `AUTH_SIGNING_JWK` | ES256 signing key (JWK) | see [ADR-008](../../decisions/ADR-008-token-details-and-revocation.md) |
  | `ALLOW_DEVELOPMENT_SECRETS=false`, `AUTH_ALLOW_EPHEMERAL_SIGNING_KEY=false` | Fail closed | — |
  | `TRUSTED_PROXIES` | Load balancer address range | from the platform's network setup |

- **Changing an encryption key** makes existing ciphertexts unreadable: webhook secrets would need rotation by merchants; TOTP secrets need re-enrollment of every admin. Rotation with key ids is an open item.
- **A real secret was committed:** rotate it first (it's public now, whatever happens to Git), then remove it from the code. Rewriting history doesn't un-leak it.
- **Never** add a gitleaks exception for a real secret, and never one without a reason.
