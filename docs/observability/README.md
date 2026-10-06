# Observability (as built)

Design and target stack: [blueprint/15-observability-monitoring.md](../blueprint/15-observability-monitoring.md). Introduced progressively; P1 provides logs, health and metrics. Traces arrive in P6.

## What we observe, why, and where the signal comes from

| Signal | Why | Source | Where to look |
|---|---|---|---|
| **Structured logs** (ECS JSON) | Investigate individual requests and errors | Spring Boot structured logging (`logging.structured.format.console=ecs`); stdout | `docker compose logs app`, later Loki |
| `requestId` field | Connect a client-visible error to its log lines | `RequestIdFilter` → MDC | Every log line written during a request |
| `service.name/version/environment` | Know which build and environment emitted a line | `spring.application.name`, build-info, `ENVIRONMENT` | Every log line |
| **Liveness** | "Is the process alive?" Restart if not | `livenessState` only | `:8081/actuator/health/liveness` |
| **Readiness** | "Can it serve traffic now?" Remove it from the LB if not | `readinessState` + `db` | `:8081/actuator/health/readiness` |
| `http_server_requests_seconds_*` (histogram) | RED metrics: rate, errors (`status`/`outcome` labels), duration percentiles | Spring MVC observation | `:8081/actuator/prometheus` |
| `hikaricp_connections_{active,idle,pending}`, `_timeout_total` | Pool saturation is the earliest sign of DB trouble or long transactions | Hikari | same |
| `jvm_*`, `process_*` | Memory, GC, threads, CPU | Micrometer binders | same |
| `storeId` log field | Which tenant a log line belongs to (per-tenant debugging without a metrics label) | `TenantTransactions` (MDC) | Log lines written inside tenant transactions |
| `userId` log field | Who made the request (an ID, never the email) | `UserMdcFilter` after bearer authentication | Log lines of authenticated `/admin/**` requests |
| `storeforge_auth_logins_total{outcome}` | Login outcomes: `success`, `invalid_credentials`, `locked`, `disabled`, `email_not_verified`. The client sees one generic answer; we keep the real reason here | `LoginService` | `/actuator/prometheus` |
| `storeforge_auth_token_refreshes_total{outcome}` | `rotated`, `reuse_detected` (possible token theft), `invalid` | `TokenService` | same |
| `storeforge_auth_registrations_total` | Signups | `RegistrationService` | same |
| `storeforge_ratelimit_rejected_total{limiter}` | 429s per limiter (`auth-ip`, `auth-email`): attack or misbehaving client | `RateLimiter` | same |
| `storeforge_notifications_emails_total{outcome}` | `sent` / `failed` | `SmtpEmailSender` | same |
| `application="storeforge"` common tag | Distinguish services once there are several | `management.metrics.tags` | all metrics |

Log levels ([blueprint conventions](../blueprint/15-observability-monitoring.md#logs)): ERROR = unexpected, needs a human (currently only `ApiExceptionHandler`); WARN = handled degradation; INFO = business-significant state changes; DEBUG off by default.

## How to investigate a failure

1. Get the `requestId` from the client (response header `X-Request-Id` or the `requestId` field of the problem body).
2. Find all log lines for it: `docker compose logs app --no-log-prefix | jq 'select(.requestId=="<id>")'`.
3. The ERROR line contains `error.type`, `error.message`, `error.stack_trace`. The client got an opaque 500.
4. If many requests fail: check readiness (`db` component) and `hikaricp_connections_pending` / `_timeout_total`.

## Security signals worth watching
- `reuse_detected` > 0: a refresh token was replayed. Investigate the WARN log (`userId`, `familyId`). See the [auth anomalies runbook](../operations/runbooks/auth-anomalies.md).
- `invalid_credentials` and `locked` rising together with `auth-ip` rejections: credential stuffing.

## Sensitive data

The 500 response never contains exception details; the server log does. Credentials, tokens and email links never appear in output; `SensitiveDataLoggingIT` asserts this over the auth flows.
