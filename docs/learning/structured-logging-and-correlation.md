# Structured Logging, Request IDs and Problem Details

**Problem.** Plain-text logs from many concurrent requests interleave, and you cannot find "all lines for the request that failed for this customer". Free-text logs can't be filtered by field. Error responses in ad-hoc shapes force clients to parse prose.

**Why we need it.** Debugging a multi-tenant platform means slicing logs by request, tenant, user and service version. Clients (our SPAs, third-party apps) need machine-readable errors.

**Alternatives.** Text logs + grep; vendor agents; only trace IDs (OTel, from P6) without a human-facing ID; custom error JSON.

**Decision.** ECS-format JSON logs (Spring Boot built-in structured logging) + `X-Request-Id` + RFC 9457 Problem Details ([ADR-007](../decisions/ADR-007-error-and-correlation-model.md)).

**Mental model.**
- **MDC** (Mapped Diagnostic Context) is a per-thread map that the log encoder adds to every line. `RequestIdFilter` puts `requestId` in at the start of a request and removes it in `finally`. Pooled threads would otherwise carry it into the next request.
- With virtual threads, each request runs on its own virtual thread, so MDC works the same. Work handed to *other* threads (executors, async jobs) does not inherit MDC automatically; P9 propagates context explicitly with each job.
- **Problem Details** separates the stable contract (`status`, `code`, `type`) from human text (`title`, `detail`).

**Project usage.** `shared/web/RequestIds`, `RequestIdFilter`, `ApiExceptionHandler`; `logging.structured.format.console=ecs`.

**Configuration.** `LOG_FORMAT` (ecs/logstash/gelf), `ENVIRONMENT`.

**Failure cases.** Logging secrets (rule: log IDs, never objects or headers); high-volume DEBUG in production; log forging via unvalidated input (mitigated for the request ID); MDC leaks across threads.

**Testing.** `RequestIdsTest` (validation incl. newline injection), `ErrorResponseIT` (problem format, header propagation, ERROR log line contains the request ID while the response contains no internals).

**Observability.** It *is* observability: see [observability/README.md](../observability/README.md) for how to investigate with it.

**When NOT to use it.** Logs are not metrics: don't count things by logging them and grepping. Use counters.
