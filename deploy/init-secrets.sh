#!/usr/bin/env bash
# Generates the secrets of ONE environment into deploy/secrets/<env>/ (gitignored), once.
# Files are named after the variables they replace; the app reads them via configtree (/run/secrets).
# In a real deployment these come from the platform's secret store; never commit or copy them.
set -euo pipefail
ENV=${1:?usage: deploy/init-secrets.sh staging|production}
DIR="$(dirname "$0")/secrets/$ENV"
if [ -d "$DIR" ]; then echo "$DIR exists; not overwriting (delete it deliberately to regenerate)"; exit 0; fi
mkdir -p "$DIR" && chmod 700 "$DIR"
rnd() { openssl rand -base64 "$1" | tr -d '\n'; }
alnum() { openssl rand -hex "$1"; }
printf '%s' "$(alnum 24)" > "$DIR/POSTGRES_PASSWORD"            # superuser: provisioning and backups only
printf '%s' "$(alnum 24)" > "$DIR/DB_OWNER_PASSWORD"            # migrations (migrate job only)
printf '%s' "$(alnum 24)" > "$DIR/DB_APP_PASSWORD"              # runtime
printf '%s' "$(rnd 32)" > "$DIR/WEBHOOK_ENCRYPTION_KEY"
printf '%s' "$(rnd 32)" > "$DIR/PLATFORM_MFA_ENCRYPTION_KEY"
printf '%s' "app-$(alnum 8)" > "$DIR/STORAGE_ACCESS_KEY"
printf '%s' "$(alnum 24)" > "$DIR/STORAGE_SECRET_KEY"
printf '%s' "backup-$(alnum 8)" > "$DIR/BACKUP_ACCESS_KEY"
printf '%s' "$(alnum 24)" > "$DIR/BACKUP_SECRET_KEY"
printf '%s' "whsec_$(alnum 24)" > "$DIR/FAKE_PROVIDER_WEBHOOK_SECRET"
printf '%s' "$(alnum 16)" > "$DIR/GRAFANA_ADMIN_PASSWORD"
python3 - "$DIR/AUTH_SIGNING_JWK" <<'PY'
import base64, hashlib, json, sys
from cryptography.hazmat.primitives.asymmetric import ec
b64 = lambda n: base64.urlsafe_b64encode(n.to_bytes(32, "big")).rstrip(b"=").decode()
key = ec.generate_private_key(ec.SECP256R1())
pub = key.public_key().public_numbers()
thumb = json.dumps({"crv": "P-256", "kty": "EC", "x": b64(pub.x), "y": b64(pub.y)}, separators=(",", ":"), sort_keys=True)
kid = base64.urlsafe_b64encode(hashlib.sha256(thumb.encode()).digest()).rstrip(b"=").decode()
jwk = {"kty": "EC", "crv": "P-256", "use": "sig", "kid": kid, "x": b64(pub.x), "y": b64(pub.y), "d": b64(key.private_numbers().private_value)}
open(sys.argv[1], "w").write(json.dumps(jwk))
PY
# Object storage identities: the app reads/writes media; the backup identity only the backups bucket.
python3 - "$DIR" <<'PY'
import json, os, sys
d = sys.argv[1]; r = lambda n: open(os.path.join(d, n)).read()
json.dump({"identities": [
    {"name": "app", "credentials": [{"accessKey": r("STORAGE_ACCESS_KEY"), "secretKey": r("STORAGE_SECRET_KEY")}],
     "actions": ["Read:storeforge-media", "Write:storeforge-media", "List:storeforge-media", "Admin:storeforge-media"]},
    {"name": "backups", "credentials": [{"accessKey": r("BACKUP_ACCESS_KEY"), "secretKey": r("BACKUP_SECRET_KEY")}],
     "actions": ["Read:storeforge-backups", "Write:storeforge-backups", "List:storeforge-backups", "Admin:storeforge-backups"]}]},
    open(os.path.join(d, "s3.json"), "w"))
PY
# Readable inside the containers (their users aren't you); the 700 directory keeps them private on the host.
chmod 644 "$DIR"/*
echo "secrets for $ENV in $DIR"
