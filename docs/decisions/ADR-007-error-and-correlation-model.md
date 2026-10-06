# ADR-007: RFC 9457 problem details + request IDs

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P1

## Context
Every API (storefront, admin, developer, platform) needs one error format that clients can parse and that support can correlate with server logs.

## Problem
What do error responses look like, and how is a request traced from a client report to logs?

## Options Considered
1. Ad-hoc `{ "error": "..." }` bodies. Inconsistent; nothing to switch on.
2. **RFC 9457 Problem Details** (`application/problem+json`, `type/title/status/detail/instance`) with extension members. A standard with native Spring support (`ProblemDetail`).
3. GraphQL-style error arrays. They don't fit REST.

For correlation: rely only on OpenTelemetry trace IDs, or add a human-friendly request ID too.

## Decision
- Problem Details for all errors, with extensions `code` (stable machine-readable, snake_case) and `requestId`. Later: `errors[]` for field validation.
- `X-Request-Id`: accepted from clients if it matches `[A-Za-z0-9-]{8,64}`, otherwise generated (32 hex chars). Set first in the filter chain, put into MDC (so it is in every JSON log line), echoed in the response.
- Unexpected exceptions: one ERROR log with the stack trace, and an opaque 500 response.

## Why We Chose It
- Standard format: clients and tools understand it, and Spring maps its own exceptions to it.
- `code` decouples client logic from human-readable text.
- The request ID works before tracing exists (P6) and remains useful after: it is short, user-visible, and can be supplied by clients for their own retries.
- Validating the incoming header prevents log forging (newline injection) and abuse through huge values.

## Trade-offs
- Two correlation identifiers (request ID + trace ID) once OTel is added. They are documented as "human" vs. "tooling" IDs.
- Spring-generated `detail` texts (e.g. "No endpoint GET /products.") are exposed. They are harmless, but they are framework wording.

## Consequences
- Domain exceptions (P2+) carry their own `code` and status. The handler maps them.
- Tests assert on `code`, never on `title`/`detail` text.
