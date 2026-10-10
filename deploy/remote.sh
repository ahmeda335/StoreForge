#!/usr/bin/env bash
# CD helper: syncs deploy/ to the host (configuration only: never secrets, generated there once) and runs
# deploy.sh there. Needs SSH_KEY (private key) and HOST (user@host) in the environment.
set -euo pipefail
ENV=${1:?environment}; IMAGE=${2:?image}
KEY=$(mktemp); trap 'rm -f "$KEY"' EXIT
printf '%s\n' "$SSH_KEY" > "$KEY"; chmod 600 "$KEY"
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new)
# Same layout as the repository (compose.yaml refers to ../infra), secrets and state stay on the host.
rsync -azR --delete --exclude deploy/secrets/ --exclude deploy/state/ -e "${SSH[*]}" deploy infra "$HOST:storeforge/" >/dev/null
"${SSH[@]}" "$HOST" "cd storeforge/deploy && ./deploy.sh $ENV $IMAGE"
