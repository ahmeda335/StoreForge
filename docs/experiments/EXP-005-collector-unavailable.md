# EXP-005: Telemetry collector unavailable

- **Date:** 2026-10-06 · **Phase:** P6 · **Setup:** app from the jar with `SPRING_PROFILES_ACTIVE=otel`, exporting to `grafana/otel-lgtm` on `localhost:4318`. The container was recreated while the app was running (not originally planned as an experiment; observed while provisioning the dashboard, and recorded because it is a real failure mode).

## Experiment
`docker compose --profile observability up -d --force-recreate otel-lgtm` while the app was serving and exporting.

## Expected behavior
- Requests keep working with unchanged latency: telemetry export is asynchronous (batch processors, background threads).
- Telemetry produced during the gap is lost (no persistent buffering in the app).
- Export resumes automatically when the Collector is back.

## Actual behavior
| Observation | Value |
|---|---|
| App ERROR log lines | 4, from `io.opentelemetry.exporter.internal.http.HttpExporter`: `Failed to export logs ... unexpected end of stream on http://localhost:4318/` (one per second) |
| Request handling | Unaffected (the traffic run afterwards produced normal latencies) |
| After restart | Traces, metrics and logs flowed again without an app restart; the dashboard showed fresh data |
| Data loss | Telemetry of the ~4 s gap, plus everything stored in the recreated container (it has no persistent volume) |

## What failed / why
Nothing functional. The exporter's failure logging is at ERROR, which conflicts with our convention (ERROR = needs a human, P1). A Collector restart is an infrastructure event, not an application error.

## How it was detected
ERROR lines in the app's own stdout log (not visible in Loki, for the obvious reason).

## How the system recovered
Automatically. The batch exporters retry with the next batch.

## What we changed
Nothing yet. Follow-ups:
- (P16) In production the Collector runs as a sidecar/agent with its own queue and retry, so an app→Collector outage on localhost is rare and short.
- (P16) Consider lowering `io.opentelemetry.exporter` logging to WARN, and alerting on the exporter's own metrics instead.
- Lesson: telemetry pipelines are best-effort by design. **Never** make request handling wait on telemetry.
