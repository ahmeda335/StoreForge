#!/usr/bin/env bash
# Creates (or RE-creates) the load-test database "storeforge_load" in the Compose PostgreSQL, with
# the same roles, schema and privileges as the real one (infra/postgres/init/01-create-roles.sh).
# Your development database "storeforge" is not touched. Flyway creates the tables when the app
# starts with DB_URL=jdbc:postgresql://localhost:5432/storeforge_load.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'EOSQL'
DROP DATABASE IF EXISTS storeforge_load WITH (FORCE);
CREATE DATABASE storeforge_load;
REVOKE ALL ON DATABASE storeforge_load FROM PUBLIC;
GRANT CONNECT ON DATABASE storeforge_load TO storeforge_app;
GRANT CONNECT, CREATE ON DATABASE storeforge_load TO storeforge_owner;
EOSQL
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d storeforge_load <<'EOSQL'
CREATE SCHEMA storeforge AUTHORIZATION storeforge_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA storeforge TO storeforge_app;
GRANT USAGE, CREATE ON SCHEMA storeforge TO storeforge_tenant_scanner;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
EOSQL
echo "storeforge_load ready"
