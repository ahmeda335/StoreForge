#!/usr/bin/env bash
# Bulk history for every store in load/data/stores.json (after create-stores.py), then ANALYZE.
#   large: 20,000 products, 50,000 customers, 200,000 orders; medium: 2,000 / 5,000 / 20,000; small: 100 / 200 / 500
set -euo pipefail
# Resumable: stores that already have products are skipped.
cd "$(dirname "$0")/../.."
python3 -c "
import json
sizes={'large':(20000,50000,200000),'medium':(2000,5000,20000),'small':(100,200,500)}
for s in json.load(open('load/data/stores.json'))['stores']:
    print(s['id'], *sizes[s['size']])" | while read -r store products customers orders; do
  if [ "$(docker compose exec -T postgres psql -At -U postgres -d storeforge_load -c "SELECT count(*) FROM storeforge.products WHERE store_id = '$store'" < /dev/null)" != "0" ]; then
    echo "skip $store (already seeded)"; continue
  fi
  docker compose exec -T postgres psql -q -U postgres -d storeforge_load -v store="$store" -v products="$products" \
      -v customers="$customers" -v orders="$orders" < load/seed/seed-store.sql | grep -E "ERROR" || true
  echo "seeded $store ($products products, $orders orders)"
done
docker compose exec -T postgres psql -q -U postgres -d storeforge_load -c "ANALYZE"
