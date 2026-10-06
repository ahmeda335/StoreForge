# 15 — Observability and 16 — Monitoring and Alerting

Observability answers *"what is happening and why?"* for questions you did not anticipate. Monitoring answers *"is something I know matters broken?"*. Both are designed **with** each feature: a feature's Definition of Done includes its signals.

## Standard: OpenTelemetry

**Problem it solves:** every vendor had its own agent, SDK and wire format, so instrumenting code meant vendor lock-in. OTel provides one API/SDK and one protocol (**OTLP**) for traces, metrics and logs. The backend can be swapped without touching code.

In Spring Boot 4: Micrometer Observation API (instrumentation inside Spring), bridged to OTel via `spring-boot-starter-opentelemetry` (OTLP export of traces and metrics). Logs stay **structured JSON on stdout** and are shipped by the collector/agent. This follows twelve-factor practice; the app does not ship its own logs.

## Logs

- **Format:** JSON (Spring Boot structured logging, ECS format) in every environment except optionally a human console format locally.
- **Fields:** `@timestamp`, `log.level`, `message`, `logger`, `thread`, `service.name`, `service.version`, `environment`, `requestId`, `traceId`, `spanId`, `storeId`, `userId`/`principal` (id only), `error.type`, `error.stack_trace`.
- **Levels:** ERROR = needs human attention (unexpected failures). WARN = degraded but handled (retry scheduled, fallback used). INFO = business-significant events (order placed, payment captured, job dead), one line per meaningful state change, not per method. DEBUG = development diagnostics (off in prod; can be enabled per logger at runtime via actuator).
- **Redaction:** never log passwords, tokens, API keys, `Authorization`/`Cookie` headers, full emails in high-volume logs (hash or mask), payment provider raw responses unredacted, or webhook secrets. Enforced by (1) logging IDs not objects (no `log.info("{}", user)`), (2) `toString()` on credential-bearing types returning `[REDACTED]`, (3) a test that scans captured logs during the integration suite for known secret patterns.

## Metrics: types

- **Counter:** monotonically increasing count. Query with `rate()`.
- **Gauge:** a value that goes up and down, sampled.
- **Histogram:** distribution of values in buckets → percentiles (p50/p95/p99) computed server-side, aggregatable across instances (unlike client-side summaries).

| Metric | Type | Labels (low cardinality only) | Source |
|---|---|---|---|
| `http.server.requests` (Prometheus: `http_server_requests_seconds_*`) | Histogram (count + sum + buckets) | method, uri template, status, outcome | Spring auto |
| `http_requests_total` | = `_count` of the above | | |
| `db.client.connections.*` (HikariCP active/idle/pending/timeouts) | Gauges + counter | pool | Auto |
| `database_query_duration` | Histogram | query name (from `@Observed`/JDBC observation), not raw SQL | Datasource observation |
| `storeforge.orders.created` | Counter | channel (storefront/api) | OrderService |
| `storeforge.payments.failed` | Counter | provider, reason class (declined/timeout/error) | payments |
| `storeforge.inventory.reservation.failures` | Counter | reason (insufficient/deadlock_retry) | inventory |
| `storeforge.webhook.deliveries` | Counter | outcome (success/retry/dead) | webhooks |
| `storeforge.webhook.delivery.duration` | Histogram | outcome | webhooks |
| `storeforge.jobs.queue.depth` | Gauge | queue, status | jobs (periodic `COUNT` on partial index) |
| `storeforge.jobs.oldest.age` | Gauge (seconds) | queue | jobs. Better than depth for alerting: "work is waiting too long" |
| `storeforge.outbox.unpublished` | Gauge | — | jobs |
| `storeforge.ratelimit.rejected` | Counter | api (storefront/admin/developer/auth) | rate limiter |
| JVM: heap, GC pause, threads | Gauges/histograms | Auto | |

**No `storeId` label on metrics.** Per-tenant analysis uses traces/logs (high cardinality is fine there) and dedicated top-N tracking in the rate limiter.

## Distributed tracing

- **Trace:** the whole journey of one request, possibly across processes and async hops. **Span:** one timed operation within it (HTTP handler, SQL query, provider call) with a parent. **Attributes:** key-values on spans (`storeforge.store_id`, `http.route`, `db.operation`). **Context propagation:** passing trace id + parent span id across boundaries (W3C `traceparent` header over HTTP, and in our case **stored in `outbox_events.trace_context` and `jobs.trace_context`** to cross the async boundary).
- **Instrumentation:** automatic (Spring MVC, JDBC via datasource-micrometer, RestClient/HTTP client) plus manual `Observation`s for business operations (`checkout.complete`, `inventory.reserve`, `webhook.deliver`).
- **Sampling:** head sampling 100% locally, ~10% in prod, plus **tail sampling in the Collector** to keep 100% of error and slow traces (the Collector sees the whole trace before deciding).

```text
POST /storefront/v1/checkouts/{id}/completion          [http server span]
 └─ checkout.complete                                 [observation]
     ├─ inventory.reserve                             [observation]
     │   └─ UPDATE inventory_items ...                [jdbc span]
     ├─ INSERT orders / order_items / outbox_events   [jdbc spans]
     └─ payments.start → POST api.stripe.com          [http client span]
 ... async, linked via traceparent stored in outbox ...
outbox.relay → job webhook.deliver                    [consumer span, parent = original trace]
 └─ POST https://merchant.example/hooks               [http client span]
```

## Recommended stack

```text
App (OTLP) ──▶ OpenTelemetry Collector ──▶ Prometheus (metrics, remote-write or scrape)
stdout JSON ─▶ (Collector filelog / Docker log driver) ─▶ Loki (logs)
                                         └▶ Tempo (traces)
                         Grafana (dashboards, alerting, trace↔log↔metric links via traceId and exemplars)
```
Locally this is the **`grafana/otel-lgtm`** single container (Collector + Prometheus + Loki + Tempo + Grafana), introduced in the observability phase.

Alternatives: **Jaeger** (traces only; simple), **SigNoz** (all-in-one on ClickHouse, OTel-native), **Elastic/OpenSearch observability**, **Honeycomb** (excellent high-cardinality trace analysis, SaaS), **Datadog/New Relic/Grafana Cloud** (managed, costly). Because we emit OTLP, switching is a collector config change.

## Monitoring: SLIs, SLOs, error budgets

- **SLI** (indicator): a measured ratio of good events. **SLO** (objective): the target for an SLI over a window. **Error budget** = 1 − SLO: the amount of failure you may "spend" (deploys, experiments) before freezing changes.

| SLI | SLO (28 days) | Notes |
|---|---|---|
| Storefront availability: non-5xx / all requests on `/storefront/**` | 99.9% | ~40 min budget |
| Storefront latency: requests < 300 ms | 99% | |
| Checkout completion success (excluding card declines) | 99.5% | Declines are customer outcomes, not our errors |
| Checkout latency < 1.5 s | 95% | Includes provider call |
| Webhook delivery: first attempt within 60 s of event | 99% | Measures our pipeline, not their endpoint |
| Payment webhook processing lag < 30 s | 99.9% | |

## Alerts (symptom-based first; burn-rate for SLOs)

| Alert | Condition | Why it matters |
|---|---|---|
| Storefront SLO fast burn | Error rate burning 14× budget over 1h (and 5m confirming) | Customers cannot buy right now; page someone |
| Storefront SLO slow burn | 6× over 6h | Will exhaust budget in days; ticket |
| Checkout success drop | Success ratio < 97% for 10 min | Direct revenue loss |
| Payment failures spike (non-decline) | `payments_failed{reason!="declined"}` rate > baseline × 5 | Provider outage or our integration broke |
| Job backlog aging | `jobs_oldest_age > 300s` | Emails/webhooks/payment follow-ups delayed; workers dead or stuck |
| Dead jobs | Any increase in DEAD jobs | Something permanently failed and needs a human |
| Outbox not draining | `outbox_unpublished > 0` and not decreasing for 5 min | Relay is down → all async work stops |
| DB connection pool saturation | `hikaricp_connections_pending > 0` for 5 min or timeouts > 0 | Precursor to request failures; often long transactions or provider calls inside a TX |
| DB health | Down / replication lag > 30s / disk > 80% | Everything depends on it |
| Webhook failure ratio | Delivery failure ratio > 20% platform-wide | Our dispatcher (not one merchant) is broken |
| Instance health | Readiness failing, restarts > 3/15 min, heap > 90% after GC | Capacity, memory leaks |
| Disk/CPU | Disk > 80%, CPU saturation sustained 15 min | Capacity planning (warning, not page) |

Don't alert on causes that don't hurt users (CPU 90% with healthy latency is not an emergency). Every alert links a **runbook** in `docs/operations/runbooks/`.
