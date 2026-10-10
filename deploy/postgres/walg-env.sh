#!/usr/bin/env bash
# Runs wal-g with credentials read from Docker secret files (never in the environment of the server).
#   walg-env wal-push %p      (archive_command)
#   walg-env backup-push $PGDATA
set -euo pipefail
export AWS_ACCESS_KEY_ID="$(cat /run/secrets/BACKUP_ACCESS_KEY)"
export AWS_SECRET_ACCESS_KEY="$(cat /run/secrets/BACKUP_SECRET_KEY)"
export WALG_COMPRESSION_METHOD=zstd
exec wal-g "$@"
