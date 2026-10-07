# Local Development

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| JDK | 21 | Language level (`java -version`) |
| Docker + Compose v2 | 24+ / 2.20+ | PostgreSQL locally; Testcontainers in `./mvnw verify` |
| Maven | not needed | `./mvnw` downloads Maven 3.9.11 |

## Services

| Service | How | Ports | Notes |
|---|---|---|---|
| PostgreSQL 17 | `docker compose up -d` | 5432 (`POSTGRES_PORT` to change) | Roles created by `infra/postgres/init/01-create-roles.sh` on first start |
| Mailpit | `docker compose up -d` | 1025 (SMTP), [8025 (web UI)](http://localhost:8025) | Catches every email the app sends |
| Observability (Collector, Prometheus, Tempo, Loki, Grafana) | `docker compose --profile observability up -d` | [3000 Grafana](http://localhost:3000) (admin/admin), 4317/4318 OTLP; 127.0.0.1:9090/3200/3100 APIs | Run the app with `SPRING_PROFILES_ACTIVE=otel` to send telemetry; data is not persisted |
| App (container) | `docker compose --profile app up -d --build` | 8080, 127.0.0.1:8081 | Same image as CI builds |
| App (host) | `./mvnw spring-boot:run` | 8080, 8081 | Fastest dev loop |

## Environment variables

All have local defaults in `application.yml`; override them via the environment or a gitignored `.env` (Compose reads it; see `.env.example`).

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/storeforge` | JDBC URL |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `storeforge_app` / `storeforge_app_dev` | Runtime role |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | `storeforge_owner` / `storeforge_owner_dev` | Migration role |
| `DB_POOL_SIZE` | 10 | Hikari max pool size |
| `SERVER_PORT` / `MANAGEMENT_PORT` | 8080 / 8081 | HTTP ports |
| `LOG_FORMAT` | `ecs` | Structured log format (`ecs`, `logstash`, `gelf`) |
| `ENVIRONMENT` | `local` | Written to every log line as `service.environment` |
| `POSTGRES_PASSWORD` | `postgres_dev` | Superuser (Compose only) |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_FROM` | `localhost` / `1025` / `StoreForge <no-reply@…>` | SMTP |
| `APP_BASE_URL` | `http://localhost:4200` | Where email links point (the future SPA) |
| `AUTH_ISSUER` | `https://storeforge.localhost` | JWT `iss` |
| `AUTH_SIGNING_JWK` | empty → ephemeral key (WARN at startup) | Private EC P-256 JWK; set it to keep tokens valid across restarts |
| `AUTH_PREVIOUS_PUBLIC_JWKS` | empty | JWK Set of old public keys during rotation |
| `AUTH_ALLOW_EPHEMERAL_SIGNING_KEY` | `true` | Must be `false` outside local dev |
| `AUTH_ALLOWED_ORIGINS` | `http://localhost:4200` | Origins allowed to call refresh/logout |
| `AUTH_RATE_LIMIT_PER_IP` / `_PER_EMAIL` | 30 / 10 per minute | Credential endpoint limits |
| `SPRING_PROFILES_ACTIVE` | (none) | `otel` enables OTLP export of traces, metrics and logs |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Collector base URL (OTLP/HTTP) |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Head sampling ratio |
| `INVENTORY_SWEEPER_INTERVAL` | `30s` | Reservation expiry sweep interval |
| `JOBS_POLL_INTERVAL` | `500ms` | Outbox relay + job worker poll |
| `ORDER_PAYMENT_WINDOW` / `ORDER_EXPIRY_INTERVAL` | `15m` / `30s` | Unpaid-order expiry |
| `APP_PROFILES` (Compose) | `default` | Profiles for the containerized app, e.g. `otel` |

## Database

- Migrations run automatically at app startup (Flyway, as `storeforge_owner`).
- Connect as the app would: `docker compose exec postgres psql -U storeforge_app -d storeforge` (password `storeforge_app_dev`; it cannot run DDL).
- Reset everything: `docker compose down -v` (deletes the volume; the init script runs again next start).

## Generating a JWT signing key

```bash
jshell --class-path "$(find ~/.m2 -name 'nimbus-jose-jwt-*.jar' | head -1)" <<'JWK'
var key = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256)
    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyIDFromThumbprint(true).generate();
System.out.println(key.toJSONString());
JWK
```
Put the output in `.env` as `AUTH_SIGNING_JWK='…'`. It is a private key: never commit it.

## Trying the API

`http/auth.http` walks through register → verify (token from Mailpit) → login → `/admin/v1/me` → refresh → logout.

## Reading logs

Logs are JSON (one object per line). For humans:
```bash
./mvnw spring-boot:run | jq -r '"\(.["@timestamp"]) \(.log.level) [\(.requestId // "-")] \(.message)"'
docker compose logs -f app --no-log-prefix | jq .
```

## Tests

See [testing/README.md](../testing/README.md). `./mvnw verify` needs Docker running; it starts its own PostgreSQL container and does not touch the Compose database.

## Later phases add

Redis (P12), MinIO (P13), k6 (P15). Each comes behind its own Compose profile.
