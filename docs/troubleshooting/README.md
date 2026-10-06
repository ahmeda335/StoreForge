# Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `./mvnw verify` fails with `Could not find a valid Docker environment` | Docker daemon not running / no permission | Start Docker; ensure your user can run `docker ps` |
| App fails at startup: `FATAL: password authentication failed for user "storeforge_owner"` | Compose volume created with other passwords, or a `.env` mismatch | Align `.env` with the volume, or `docker compose down -v` to re-provision (deletes data) |
| App fails at startup: `role "storeforge_app" does not exist` | The volume existed before the init script was added; init scripts only run on an empty data dir | `docker compose down -v && docker compose up -d` |
| `bind: address already in use` on 5432 | Another PostgreSQL on the host | `POSTGRES_PORT=5433 docker compose up -d` and `DB_URL=jdbc:postgresql://localhost:5433/storeforge` |
| `permission denied for schema storeforge` from the app | Expected for DDL: the app role cannot change the schema | Put schema changes in a Flyway migration |
| Logs are unreadable JSON | Structured logging is on by design (same format in every environment) | Pipe through `jq`, see [local-development.md](../infrastructure/local-development.md#reading-logs) |
| Compile fails on a warning | `-Xlint:all -Werror` | Fix the warning, or suppress it narrowly with a justification comment |
