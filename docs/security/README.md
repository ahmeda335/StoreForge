# Security (as built)

Threat analysis and target controls: [blueprint/18-security.md](../blueprint/18-security.md).

## P1 posture

| Control | Implementation | Verified by |
|---|---|---|
| Least-privilege DB access | Runtime role `storeforge_app` cannot run DDL; owner role only for migrations | `DatabaseRolesIT` |
| DB-side guards | `statement_timeout=10s`, `idle_in_transaction_session_timeout=30s` on the app role | `DatabaseRolesIT` |
| No internals in errors | 500 responses opaque; `server.error.include-stacktrace/message=never` | `ErrorResponseIT` |
| Log-forging resistance | `X-Request-Id` accepted only if it matches `[A-Za-z0-9-]{8,64}` | `RequestIdsTest` |
| Operational endpoints not public | Actuator on management port 8081; Compose binds it to 127.0.0.1 | `OperationalEndpointsIT` |
| Container hardening | Non-root user, JRE-only runtime image, exec-form entrypoint | Dockerfile review |
| Secrets out of Git | `.env` gitignored, `.env.example` with dev-only values | `.gitignore` |

## Not yet (by design)
Authentication/authorization (P2/P3), rate limiting (P2), dependency and secret scanning in CI (P14), TLS (P16 via the reverse proxy).

## Accepted risk
Actuator health shows component details including exception class names. This is acceptable only because the management port is private.
