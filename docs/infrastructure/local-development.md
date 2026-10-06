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

## Database

- Migrations run automatically at app startup (Flyway, as `storeforge_owner`).
- Connect as the app would: `docker compose exec postgres psql -U storeforge_app -d storeforge` (password `storeforge_app_dev`; it cannot run DDL).
- Reset everything: `docker compose down -v` (deletes the volume; the init script runs again next start).

## Reading logs

Logs are JSON (one object per line). For humans:
```bash
./mvnw spring-boot:run | jq -r '"\(.["@timestamp"]) \(.log.level) [\(.requestId // "-")] \(.message)"'
docker compose logs -f app --no-log-prefix | jq .
```

## Tests

See [testing/README.md](../testing/README.md). `./mvnw verify` needs Docker running; it starts its own PostgreSQL container and does not touch the Compose database.

## Later phases add

Mailpit (P2), observability stack `grafana/otel-lgtm` (P6), Redis (P12), MinIO (P13), k6 (P15). Each comes behind its own Compose profile.
