# Metric Types, Histograms and Cardinality

**Problem.** "The API is slow" needs numbers: how many requests, how many failed, how slow for the *worst* users. Averages hide tail latency. And badly labelled metrics can take down the monitoring system itself.

**Why we need it.** Dashboards (P6), SLOs and alerts (P15/P16), load-test analysis (P15).

**Alternatives.** Logs-as-metrics (grepping and counting is expensive and slow); averages (hide p99); client-side percentiles/summaries (can't be aggregated across instances).

**Decision.** Micrometer meters exported to Prometheus: counters, gauges and **histograms** (percentiles computed server-side), with low-cardinality tags only.

**Mental model.**
- **Counter:** only goes up (`storeforge_auth_logins_total`). You never read the raw value. You read `rate(x[5m])`, which is per second and robust to restarts.
- **Gauge:** a current value that goes up and down (`hikaricp_connections_active`, queue depth).
- **Histogram:** counts per latency bucket (`_bucket{le="…"}`) plus `_count` and `_sum`. `histogram_quantile(0.95, sum by (le, uri) (rate(..._bucket[5m])))` estimates p95 **across all instances**. You can't average per-instance percentiles; you can add buckets.
- **RED:** Rate, Errors, Duration per endpoint. That's the first row of the StoreForge dashboard.
- **Cardinality** = the number of distinct label combinations, i.e. the number of time series. `uri` uses route *templates* (`/products/{productId}`), never raw paths. A `store_id` label would multiply every series by the number of stores (10k stores × 50 routes × 5 statuses × 20 buckets = 50M series). That is why tenant ids go on **spans and logs**, not metrics.
- **Units:** OTLP metrics from Micrometer are in milliseconds (`_milliseconds_bucket`); the Prometheus endpoint uses seconds. Always check the name before writing a query.

**Project usage.** `management.metrics.distribution.percentiles-histogram.http.server.requests=true`; custom counters (`storeforge_*`); `infra/grafana/dashboards/storeforge-overview.json`.

**Failure cases.** Unbounded label values (user ids, raw URLs, exception messages); `rate()` over a window shorter than 2× the scrape/export interval (10s step means use ≥ 30s windows); comparing histogram quantiles across differently bucketed metrics.

**Testing.** `OperationalEndpointsIT` (Prometheus endpoint content); manual PromQL checks against the LGTM stack (P6 progress notes).

**Observability.** Prometheus's own `prometheus_tsdb_head_series` shows the series count. Watch it after adding labels.

**When NOT to use it.** Per-entity facts (this order's total, this store's request count) belong in the database, traces or logs, not in metrics.
