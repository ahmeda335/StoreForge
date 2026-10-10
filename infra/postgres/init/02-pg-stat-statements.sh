#!/usr/bin/env bash
# Statement statistics (P15). Needs shared_preload_libraries (compose.yaml). Existing volumes:
#   docker compose exec postgres psql -U postgres -d storeforge -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'
