#!/usr/bin/env bash
# Cluster-level provisioning, run once by the official postgres image on an EMPTY data directory
# (docker-entrypoint-initdb.d). In a managed database this is done by infrastructure tooling, not by
# the application: roles are cluster objects, schema objects belong to Flyway migrations.
#
#   storeforge_owner  owns the `storeforge` schema; used only by Flyway migrations
#   storeforge_app    runtime role: USAGE on the schema + DML via default privileges (V1 migration)
set -euo pipefail

: "${STOREFORGE_OWNER_PASSWORD:?must be set}"
: "${STOREFORGE_APP_PASSWORD:?must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v db="$POSTGRES_DB" \
     -v owner_password="$STOREFORGE_OWNER_PASSWORD" \
     -v app_password="$STOREFORGE_APP_PASSWORD" <<'EOSQL'
CREATE ROLE storeforge_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE storeforge_app   LOGIN PASSWORD :'app_password';

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO storeforge_app;
-- CREATE on the database lets the owner install trusted extensions (e.g. citext) from migrations.
GRANT CONNECT, CREATE ON DATABASE :"db" TO storeforge_owner;

CREATE SCHEMA storeforge AUTHORIZATION storeforge_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA storeforge TO storeforge_app;

ALTER ROLE storeforge_owner SET search_path = storeforge;
ALTER ROLE storeforge_app   SET search_path = storeforge;

-- Database-side safety nets for the runtime role. A runaway query or a transaction left open by a
-- bug (e.g. a remote call inside a transaction) is cut off instead of holding locks forever.
ALTER ROLE storeforge_app SET statement_timeout = '10s';
ALTER ROLE storeforge_app SET idle_in_transaction_session_timeout = '30s';
EOSQL
