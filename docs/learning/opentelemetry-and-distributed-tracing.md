# OpenTelemetry and Distributed Tracing

**Problem.** A request is slow. Logs say *what* happened, line by line. Metrics say *how often* and *how slow on average*. Neither shows **where the time went** in one request that crosses filters, services, SQL, and (later) workers and external APIs.

**Why we need it.** Checkout (P7), async jobs (P9) and payments (P10) span many components. Without traces, debugging them means guessing.

**Alternatives.** Logs with timestamps (manual correlation); vendor agents (lock-in); Zipkin/Jaeger client libraries (traces only); the OTel Java agent (automatic, but opaque bytecode instrumentation).

**Decision.** The Micrometer Observation API in code, the OpenTelemetry SDK underneath, and OTLP to a Collector ([ADR-012](../decisions/ADR-012-observability-pipeline.md)).

**Mental model.**
- **Trace** = one request's tree of work, identified by a 128-bit trace id. **Span** = one timed operation (name, start, duration, parent, attributes, status). The root span here is `http post /admin/v1/stores/{storeId}/...`. Its children are Spring Security spans, `connection` (waiting for/borrowing a pooled connection) and `query` spans.
- **Context propagation:** the "current span" lives in thread-local context. A child created while a parent is current becomes its child. Across processes it travels in the W3C `traceparent` header. Across *async* boundaries (jobs, P9) you must store it explicitly with the message.
- **Observation** (Micrometer) = one instrumentation call that produces a **timer metric** (low-cardinality tags) and a **span** (plus high-cardinality attributes). Spring's own HTTP/Security/scheduling instrumentation uses the same API.
- **Sampling:** head sampling decides at the root whether to record a trace (1.0 locally). Tail sampling (in the Collector, P16) decides *after* seeing the whole trace, which is how you keep all errors and slow traces while dropping most of the boring ones.
- **OTLP** is the wire protocol. The **Collector** receives, batches, samples and routes to backends (Tempo for traces, Prometheus for metrics, Loki for logs).
- **Correlation:** `traceId`/`spanId` are added to the logging MDC automatically, so every log line written inside a span points to its trace, and Loki stores them as `trace_id`/`span_id`.

**Project usage.** `spring-boot-starter-opentelemetry`; `application-otel.yml`; `logback-spring.xml` + `OpenTelemetryLogbackInstaller`; `TraceIdResponseFilter`; `TenantTransactions` (store id on the span); `DefaultInventory.reserve` (custom Observation); `datasource-micrometer` (JDBC spans).

**Configuration.** `SPRING_PROFILES_ACTIVE=otel`, `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`), `TRACING_SAMPLING_PROBABILITY`.

**Failure cases.**
- The Collector is down: data is dropped, the app is unaffected, and the exporter logs ERRORs ([EXP-005](../experiments/EXP-005-collector-unavailable.md)).
- Context lost on thread hops (executors, `@Async`, CompletableFuture), so spans become new root traces. Use context-propagating executors.
- Attribute explosion: putting request bodies or SQL parameters into spans is a privacy risk and a cost.
- A tag on the "current span" lands on whichever span is current. Here that is Spring Security's "secured request" span, not the HTTP root ([TracingIT](../../src/test/java/dev/storeforge/observability/TracingIT.java)).

**Testing.** `TracingIT` with an in-memory span exporter: a request is one trace with `query` spans and the store attribute; `inventory.reserve` is the parent of its SQL spans. `ErrorResponseIT`: problem `traceId` = `X-Trace-Id` = the log line's `traceId`.

**Observability.** It *is* observability. Verified end to end against Tempo, Prometheus and Loki (P6 progress notes).

**When NOT to use it.** Don't create a span per loop iteration or per tiny method. Spans cost CPU, memory and storage. Instrument boundaries (I/O, business operations), not everything.
