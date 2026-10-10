#!/usr/bin/env bash
# Base backup of one environment's database to object storage (wal-g). Together with continuous WAL
# archiving (archive_command, every segment and at least every 60 s) this allows point-in-time recovery.
# Schedule it daily (cron/systemd timer on the host; a managed database does this for you).
#   deploy/backup.sh staging
set -euo pipefail
cd "$(dirname "$0")"
ENV=${1:?usage: backup.sh <staging|production>}
set -a; . "env/$ENV.env"; set +a
export IMAGE=${IMAGE:-unused}
compose() { docker compose -p "storeforge-$ENV" --env-file "env/$ENV.env" -f compose.yaml "$@"; }
compose exec -T -u postgres -e PGHOST=/var/run/postgresql postgres walg-env backup-push /var/lib/postgresql/data
compose exec -T -u postgres postgres walg-env backup-list --detail 2>/dev/null | tail -3
