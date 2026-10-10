#!/usr/bin/env bash
# Restore drill (P16): proves the backups can be restored, and measures how long it takes (RTO) and
# how much data would be lost (RPO). Restores the LATEST base backup + all archived WAL into a
# throwaway container next to the live database, then compares row counts. Nothing live is touched.
#   deploy/restore-drill.sh staging
set -euo pipefail
cd "$(dirname "$0")"
ENV=${1:?usage: restore-drill.sh <staging|production>}
set -a; . "env/$ENV.env"; set +a
export IMAGE=${IMAGE:-unused}
compose() { docker compose -p "storeforge-$ENV" --env-file "env/$ENV.env" -f compose.yaml "$@"; }
live() { compose exec -T postgres psql -U postgres -d storeforge -Atc "$1"; }
COUNTS="SELECT (SELECT count(*) FROM storeforge.stores) || ' stores, ' || (SELECT count(*) FROM storeforge.orders) || ' orders, '
        || (SELECT count(*) FROM storeforge.products) || ' products, ' || (SELECT count(*) FROM storeforge.audit_log) || ' audit entries'"
NAME="storeforge-$ENV-restore-drill"
docker rm -f "$NAME" >/dev/null 2>&1 || true

echo "1. Live database: archive the current WAL segment, note the counts"
live "SELECT pg_switch_wal()" >/dev/null
sleep 5
LIVE_COUNTS=$(live "$COUNTS")
LAST_ARCHIVED=$(live "SELECT last_archived_time FROM pg_stat_archiver")
echo "   live: $LIVE_COUNTS (last WAL archived $LAST_ARCHIVED)"

echo "2. Restore LATEST base backup + WAL into a fresh container"
STARTED=$(date +%s)
NETWORK="storeforge-${ENV}_default"
POSTGRES_IMAGE=$(compose images -q postgres | head -1)
docker run -d --name "$NAME" --network "$NETWORK" -u postgres \
  -e WALG_S3_PREFIX="s3://storeforge-backups/$ENV" -e AWS_ENDPOINT=http://seaweedfs:8333 -e AWS_S3_FORCE_PATH_STYLE=true \
  -e AWS_REGION=us-east-1 -e PGDATA=/var/lib/postgresql/data \
  -v "$PWD/secrets/$ENV/BACKUP_ACCESS_KEY:/run/secrets/BACKUP_ACCESS_KEY:ro" \
  -v "$PWD/secrets/$ENV/BACKUP_SECRET_KEY:/run/secrets/BACKUP_SECRET_KEY:ro" \
  --entrypoint bash "$POSTGRES_IMAGE" -c '
    set -e
    walg-env backup-fetch "$PGDATA" LATEST
    touch "$PGDATA/recovery.signal"
    { echo "restore_command = '"'"'walg-env wal-fetch %f %p'"'"'"; echo "recovery_target_action = promote"; echo "archive_mode = off"; } >> "$PGDATA/postgresql.auto.conf"
    chmod 700 "$PGDATA"
    exec postgres -c shared_preload_libraries=pg_stat_statements' >/dev/null
for _ in $(seq 1 300); do
  if docker exec "$NAME" psql -U postgres -d storeforge -Atc "SELECT NOT pg_is_in_recovery()" 2>/dev/null | grep -q t; then break; fi
  sleep 1
done
RTO=$(( $(date +%s) - STARTED ))
RESTORED_COUNTS=$(docker exec "$NAME" psql -U postgres -d storeforge -Atc "$COUNTS")
echo "   restored: $RESTORED_COUNTS in ${RTO}s"
docker rm -f "$NAME" >/dev/null

echo "3. Result"
if [ "$LIVE_COUNTS" = "$RESTORED_COUNTS" ]; then
  echo "   RESTORE DRILL OK: identical counts; RTO ${RTO}s; RPO = time since the last archived WAL (archive_timeout 60s)"
else
  echo "   RESTORE DRILL MISMATCH: live [$LIVE_COUNTS] vs restored [$RESTORED_COUNTS]"; exit 1
fi
