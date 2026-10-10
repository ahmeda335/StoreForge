#!/usr/bin/env bash
# One failure experiment against STAGING (deployed with COMPOSE_EXTRA=chaos/compose.chaos.yaml):
# steady traffic for the whole run; the fault is injected after 60 s and healed after FAULT seconds;
# then: errors by kind and step over time, recovery time, alerts that fired, data invariants.
#   deploy/chaos/run.sh <experiment> [FAULT seconds, default 120]
# Experiments: pg-kill, pg-latency, redis-kill, lock, worker-kill, storage-down, noisy-tenant
# (storage-down: also run chaos/upload.py during the fault; the steady traffic doesn't upload.)
set -euo pipefail
cd "$(dirname "$0")/.."
EXP=${1:?experiment}; FAULT=${2:-120}
ENV=staging; set -a; . "env/$ENV.env"; set +a
export IMAGE=$(cat "state/$ENV/current") COMPOSE_EXTRA=chaos/compose.chaos.yaml
compose() { docker compose -p "storeforge-$ENV" --env-file "env/$ENV.env" -f compose.yaml -f "$COMPOSE_EXTRA" "$@"; }
toxi() { curl -sf -X "$1" "http://127.0.0.1:${OPS_PORT_PREFIX}8474$2" ${3:+-H 'Content-Type: application/json' -d "$3"} >/dev/null; }
psql_su() { compose exec -T postgres psql -U postgres -d storeforge -At "$@"; }
OUT="state/$ENV/chaos/$(date +%Y%m%d-%H%M%S)-$EXP"; mkdir -p "$OUT"
CHAOS=$(cat "state/$ENV/chaos.json")
field() { python3 -c "import json,sys;print(json.loads(sys.argv[1])[sys.argv[2]])" "$CHAOS" "$1"; }
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/timeline.txt"; }

inject() {
  case "$EXP" in
    pg-kill) compose kill postgres ;;
    pg-latency) toxi POST /proxies/postgres/toxics '{"name":"latency","type":"latency","stream":"downstream","attributes":{"latency":200,"jitter":50}}' ;;
    redis-kill) compose kill redis ;;
    lock) (psql_su -c "BEGIN; LOCK TABLE storeforge.store_counters IN EXCLUSIVE MODE; SELECT pg_sleep($FAULT); COMMIT;" >/dev/null 2>&1 &) ;;
    worker-kill) psql_su -c "UPDATE storeforge.jobs SET run_at = now() WHERE status = 'READY'" >/dev/null; compose kill -s SIGKILL worker ;;
    storage-down) compose stop seaweedfs ;;
    # From INSIDE the environment's network: Caddy sees a different client address than the steady traffic.
    noisy-tenant) docker run -d --name chaos-noisy --network "storeforge-${ENV}_default" -v "$PWD/chaos:/chaos" -w /chaos grafana/k6:2.3.0 run --quiet \
        -e HOST="$(field host)" -e PROXY="$PROXY_IP" -e RATE=200 -e DURATION="${FAULT}s" noisy.js >/dev/null ;;
    *) echo "unknown experiment $EXP"; exit 1 ;;
  esac
}
heal() {
  case "$EXP" in
    pg-kill) compose start postgres ;;
    pg-latency) toxi DELETE /proxies/postgres/toxics/latency ;;
    redis-kill) compose start redis ;;
    lock) : ;;   # the lock is released when pg_sleep ends
    worker-kill) compose start worker ;;
    storage-down) compose start seaweedfs ;;
    noisy-tenant) docker rm -f chaos-noisy >/dev/null 2>&1 || true ;;
  esac
}

# Abort (Ctrl-C, or a failed step): heal first, then stop the traffic. Healing twice is harmless.
FAULTED=""
abort() { trap - INT TERM ERR; log "ABORT"; [ -n "$FAULTED" ] && { heal || true; }; docker rm -f chaos-traffic chaos-noisy >/dev/null 2>&1 || true; exit 1; }
trap abort INT TERM ERR
pause() { sleep "$1" & wait $!; }   # a trap runs at once during `wait`, but only after a foreground sleep

TOTAL=$((60 + FAULT + 180))
log "experiment $EXP: fault ${FAULT}s; traffic for ${TOTAL}s"
docker run -d --name chaos-traffic --network host -u "$(id -u):$(id -g)" -v "$PWD/chaos:/chaos" -v "$PWD/$OUT:/out" -w /chaos grafana/k6:2.3.0 run --quiet \
  -e HOST="$(field host)" -e PORT="$HTTPS_PORT" -e VARIANT="$(field variantId)" -e DURATION="${TOTAL}s" -e OUT=/out/summary.json \
  --out json=/out/points.json traffic.js >/dev/null
pause 60; log "INJECT"; INJECTED=$(date +%s); FAULTED=1; inject
pause "$FAULT"; log "HEAL"; HEALED=$(date +%s); heal; FAULTED=""
docker wait chaos-traffic >/dev/null; docker rm chaos-traffic >/dev/null; log "traffic done"

python3 - "$OUT/points.json" "$INJECTED" "$HEALED" > "$OUT/report.txt" <<'PY'
import json, sys, collections, datetime
points, injected, healed = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
fail = collections.Counter(); per_phase = collections.Counter(); last_fail = 0; first_fail = None
req = collections.Counter(); slow = collections.Counter()
for line in open(points):
    p = json.loads(line)
    if p.get("type") != "Point": continue
    t = datetime.datetime.fromisoformat(p["data"]["time"].replace("Z", "+00:00")).timestamp()
    phase = "before" if t < injected else "during" if t < healed else "after"
    if p["metric"] == "failures":
        tags = p["data"]["tags"]; fail[(phase, tags.get("kind"), tags.get("step"))] += p["data"]["value"]
        per_phase[phase] += 1; last_fail = max(last_fail, t); first_fail = first_fail or t
    elif p["metric"] == "http_reqs":
        req[phase] += 1
    elif p["metric"] == "http_req_duration" and p["data"]["value"] > 1000:
        slow[phase] += 1
print("requests by phase:", dict(req), "| slower than 1 s:", dict(slow))
print("failures by phase:", dict(per_phase))
for (phase, kind, step), n in sorted(fail.items()):
    print(f"  {phase:6} {kind:22} {step:10} {int(n)}")
if last_fail:
    print(f"first failure {first_fail - injected:+.0f}s after inject; last failure {last_fail - healed:+.0f}s after heal")
PY
python3 -c "import json;d=json.load(open('$OUT/summary.json'));m=d['metrics'];print('orders placed:', int(m.get('orders_placed',{}).get('values',{}).get('count',0)))" >> "$OUT/report.txt" 2>/dev/null || true
curl -s "http://127.0.0.1:${OPS_PORT_PREFIX}9093/api/v2/alerts" | python3 -c "import sys,json; a=json.load(sys.stdin); print('alerts active now:', sorted({x['labels']['alertname']+'/'+x['labels'].get('api',x['labels'].get('instance','')) for x in a}) or 'none')" >> "$OUT/report.txt"
echo "invariant violations:" >> "$OUT/report.txt"; psql_su -f - < chaos/invariants.sql >> "$OUT/report.txt" 2>&1 || true
log "report: $OUT/report.txt"; cat "$OUT/report.txt"
