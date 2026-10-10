#!/usr/bin/env bash
# One measured run: resets statement statistics, runs the k6 mix, then stores next to the k6 summary
# the top statements by total time and a snapshot of the app's pool/JVM metrics.
#   load/run.sh <label> [SCALE] [DURATION]      e.g. load/run.sh baseline 1 3m
set -euo pipefail
cd "$(dirname "$0")/.."
LABEL=${1:?label}; SCALE=${2:-1}; DURATION=${3:-3m}
OUT="load/results/$(date +%Y%m%d-%H%M%S)-${LABEL}-x${SCALE}"
mkdir -p "$OUT"
psql_load() { docker compose exec -T postgres psql -q -U postgres -d storeforge_load "$@"; }
psql_load -c "SELECT pg_stat_statements_reset()" >/dev/null
docker run --rm --network host -u "$(id -u):$(id -g)" -v "$PWD/load:/load" -w /load/k6 grafana/k6:2.3.0 run --quiet \
    -e SCALE="$SCALE" -e DURATION="$DURATION" -e OUT="/${OUT}" mixed.js | tee "$OUT/k6.txt" || true
psql_load -A -F ' | ' -c "
SELECT calls, round(total_exec_time)::bigint AS total_ms, round(mean_exec_time::numeric, 2) AS mean_ms,
       round(max_exec_time::numeric, 1) AS max_ms, rows, shared_blks_read AS disk_blocks,
       left(regexp_replace(query, '\s+', ' ', 'g'), 160) AS query
  FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'storeforge_load')
 ORDER BY total_exec_time DESC LIMIT 15" > "$OUT/pg_stat_statements.txt"
curl -s localhost:8081/actuator/prometheus | grep -E '^(hikaricp_connections(_active|_pending|_usage_seconds_(count|sum)|_acquire_seconds_(count|sum|max))|jvm_gc_pause_seconds_(count|sum)|jvm_memory_used_bytes\{.*heap|process_cpu_usage|jvm_threads_live)' > "$OUT/app-metrics.txt" || true
echo "results in $OUT"
