# API Conventions (implemented)

Design rationale: [blueprint/08-api-design.md](../blueprint/08-api-design.md). This page lists only what the code **currently** guarantees.

## Ports

| Port | Purpose | Exposure |
|---|---|---|
| 8080 | Public APIs (storefront/admin/developer/platform, from P2+) | Behind the reverse proxy |
| 8081 | Actuator: `/actuator/health/{liveness,readiness}`, `/actuator/prometheus`, `/actuator/metrics`, `/actuator/info` | Private network only (Compose binds it to 127.0.0.1) |

## Request IDs

- Send `X-Request-Id: <8–64 chars of [A-Za-z0-9-]>` to choose your own ID (useful for client-side retries and logs). Anything else is replaced.
- Every response carries `X-Request-Id`. Quote it in bug reports: it is in every server log line for the request.

## Errors: `application/problem+json` (RFC 9457)

```http
HTTP/1.1 404
X-Request-Id: demo-request-001
Content-Type: application/problem+json

{
  "type": "https://docs.storeforge.dev/errors/not-found",
  "title": "Not Found",
  "status": 404,
  "detail": "No endpoint GET /products.",
  "instance": "/products",
  "code": "not_found",
  "requestId": "demo-request-001"
}
```

| Field | Stability | Use |
|---|---|---|
| `code` | **Stable contract.** Switch on this | `not_found`, `bad_request`, `method_not_allowed`, `unsupported_media_type`, `validation_failed`, `conflict`, `unauthenticated`, `forbidden`, `rate_limited`, `service_unavailable`, `internal_error`; domain-specific codes added per module |
| `type` | Stable URI derived from `code` | Documentation link |
| `title`, `detail` | Human-readable; may change | Display/logging only |
| `requestId` | Per request | Support / log lookup |

5xx responses never include exception messages, class names, SQL or stack traces.
