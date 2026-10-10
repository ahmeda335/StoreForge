#!/usr/bin/env bash
# Deploys one image to one environment, without downtime, and rolls back by itself if it fails.
#   deploy/deploy.sh staging    storeforge:abc123            (local image)
#   deploy/deploy.sh production ghcr.io/<owner>/storeforge@sha256:...   (CD: by digest)
#   deploy/deploy.sh production --rollback                   (back to the previously deployed image)
# Steps: supporting services up -> migrate job (abort here: old version keeps serving) -> api-1, wait
# healthy -> api-2, wait healthy -> worker -> smoke test -> record. Any failure after migrations:
# redeploy the previous image (no reverse migration: migrations are backward compatible, expand/contract).
set -euo pipefail
cd "$(dirname "$0")"
ENV=${1:?usage: deploy.sh <staging|production> <image|--rollback>}
TARGET=${2:?usage: deploy.sh <staging|production> <image|--rollback>}
set -a; . "env/$ENV.env"; set +a
STATE="state/$ENV"; mkdir -p "$STATE"
[ -d "secrets/$ENV" ] || { echo "no secrets for $ENV: run deploy/init-secrets.sh $ENV"; exit 1; }
PREVIOUS=$(cat "$STATE/current" 2>/dev/null || true)
if [ "$TARGET" = "--rollback" ]; then
  TARGET=$(cat "$STATE/previous" 2>/dev/null || true); ROLLBACK=1
  [ -n "$TARGET" ] || { echo "nothing to roll back to"; exit 1; }
fi
export IMAGE="$TARGET"
# COMPOSE_EXTRA: an overlay file (e.g. chaos/compose.chaos.yaml for experiments in staging).
compose() { docker compose -p "storeforge-$ENV" --env-file "env/$ENV.env" -f compose.yaml ${COMPOSE_EXTRA:+-f "$COMPOSE_EXTRA"} "$@"; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

wait_healthy() {   # service, timeout seconds
  local id; id=$(compose ps -q "$1")
  for _ in $(seq 1 "$2"); do
    case "$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null)" in
      healthy) return 0 ;;
      unhealthy) return 1 ;;
    esac
    sleep 1
  done
  return 1
}

roll_out() {   # image
  export IMAGE="$1"
  for service in api-1 api-2; do
    log "replacing $service"
    compose up -d --no-deps "$service"
    wait_healthy "$service" 120 || { log "$service did not become healthy"; return 1; }
    sleep 3   # let the proxy's health check see it before taking the next one down
  done
  log "replacing worker"
  compose up -d --no-deps worker
  wait_healthy worker 120 || { log "worker did not become healthy"; return 1; }
}

rollback() {
  if [ -z "$PREVIOUS" ] || [ "$PREVIOUS" = "$IMAGE" ]; then log "no previous image to roll back to"; exit 1; fi
  log "ROLLING BACK to $PREVIOUS"
  roll_out "$PREVIOUS" && log "rolled back to $PREVIOUS" || log "ROLLBACK FAILED: manual intervention (runbook deploy.md)"
  exit 1
}

log "deploying $IMAGE to $ENV (current: ${PREVIOUS:-none})"
compose up -d postgres redis seaweedfs mailpit caddy prometheus alertmanager grafana ${COMPOSE_EXTRA:+toxiproxy}
wait_healthy postgres 120
for bucket in storeforge-media storeforge-backups; do
  echo "s3.bucket.create -name $bucket" | compose exec -T seaweedfs weed shell >/dev/null 2>&1 || true
done
if [ -z "${ROLLBACK:-}" ]; then
  log "migrations"
  compose run --rm migrate > "$STATE/migrate.log" 2>&1 || { log "migrations failed (see $STATE/migrate.log); nothing was rolled out"; exit 1; }
fi
roll_out "$IMAGE" || rollback
compose cp caddy:/data/caddy/pki/authorities/local/root.crt "$STATE/root.crt" >/dev/null 2>&1 || true
log "smoke test"
python3 smoke.py --domain "$DOMAIN" --port "$HTTPS_PORT" --mailpit "http://127.0.0.1:${OPS_PORT_PREFIX}8025" \
    ${SMOKE_CA:-$( [ -s "$STATE/root.crt" ] && echo "--ca $STATE/root.crt" )} || rollback
if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$IMAGE" ]; then echo "$PREVIOUS" > "$STATE/previous"; fi
echo "$IMAGE" > "$STATE/current"
log "deployed $IMAGE to $ENV"
