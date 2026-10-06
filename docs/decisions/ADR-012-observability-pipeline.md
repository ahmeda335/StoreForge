# ADR-012: OpenTelemetry pipeline: Micrometer Observation in code, OTLP push, LGTM locally

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P6

## Context
P1–P5 emit JSON logs (with request ids) and a Prometheus endpoint. Debugging a slow checkout or a cross-tenant incident needs **traces** that connect HTTP, service code, SQL and background jobs, and that link to logs and metrics.

## Problem
How do we instrument the code, how does telemetry leave the process, and what runs locally?

## Options Considered
- **Instrumentation API:** the OpenTelemetry API directly in our code, or the **Micrometer Observation API** (what Spring itself uses, so one call yields a timer metric *and* a span), or the OTel Java agent (zero code, but bytecode magic and less control).
- **Transport:** Prometheus scrape for metrics + OTLP for traces, or **OTLP push for traces, metrics and logs** to a Collector. We keep the Prometheus endpoint for local inspection and tests.
- **Logs:** stdout only (shipped later by an agent), or **stdout JSON + the OTel logback appender over OTLP**.
- **Local backend:** Jaeger (traces only), SigNoz, or **`grafana/otel-lgtm`** (Collector + Prometheus + Tempo + Loki + Grafana in one container).

## Decision
- Spring Boot 4 `spring-boot-starter-opentelemetry` (Micrometer Observation → OTel SDK). Custom business observations use `Observation` with explicit **low-cardinality** (metric tags) vs. **high-cardinality** (span-only) key values.
- JDBC spans via `datasource-micrometer` (connections and queries only; never parameter values).
- Export over OTLP/HTTP, **only when the `otel` profile is active**. Tests and plain local runs export nothing. Sampling probability is 1.0 locally (`TRACING_SAMPLING_PROBABILITY`).
- Logs: JSON on stdout **and** OTLP via `OpenTelemetryAppender` (installed after the SDK bean exists). MDC `requestId`, `storeId`, `userId` are captured as log attributes.
- Correlation: `X-Request-Id` (human), `X-Trace-Id` response header and `traceId` in problem details (tooling), `traceId`/`spanId` in every log line.
- Tenant: `storeforge.store_id` is a **span attribute**, never a metric label.
- Local: Compose profile `observability` → `grafana/otel-lgtm:0.35.0` with a provisioned "StoreForge Overview" dashboard.

## Why We Chose It
- One instrumentation call gives both a metric and a trace, consistent with Spring's own instrumentation.
- OTLP decouples the app from the backend. Switching to Grafana Cloud, Honeycomb or SigNoz is a Collector/endpoint change.
- An all-in-one container makes the full click-path (dashboard → trace → logs) available on a laptop.

## Trade-offs
- Two log paths (stdout and OTLP) during the learning phase. In production one would usually pick one (typically stdout + a node agent, or OTLP only).
- The OTLP exporters log an ERROR per failed batch when the Collector is down ([EXP-005](../experiments/EXP-005-collector-unavailable.md)). That is noisy, but it is not a functional failure.
- Micrometer's OTLP metrics use milliseconds, so Prometheus names end in `_milliseconds_*`. Dashboards depend on these names.

## Consequences
- Every important new flow adds an Observation with deliberate key-value cardinality, plus a `TracingIT`-style assertion where the span structure matters.
- Production (P16) adds tail sampling in the Collector and lower head sampling.
