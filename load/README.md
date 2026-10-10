# Load testing (P15)

Reproducible load runs against a dedicated database (`storeforge_load`); your development data is not touched.

| Step | Command | Notes |
|---|---|---|
| 1. Services | `docker compose up -d` | PostgreSQL with `pg_stat_statements` |
| 2. Database | `load/setup-database.sh` | Drops and recreates `storeforge_load` (same roles and privileges) |
| 3. App | `./mvnw -DskipTests package && load/run-app.sh` | Fixed JVM (1 GB heap, G1); `DB_POOL_SIZE`, `JAVA_EXTRA` to vary |
| 4. Tenants | `python3 load/seed/create-stores.py` | 50 stores through the API → `load/data/stores.json` |
| 5. History | `load/seed/seed.sh` | ~400k orders, 38k products, 95k customers (bulk SQL), then `ANALYZE` |
| 6. Run | `load/run.sh <label> [SCALE] [DURATION]` | k6 mix + top statements + pool/JVM metrics in `load/results/…` |

**Data shape:** 1 large store (20,000 products, 50,000 customers, 200,000 orders), 9 medium (2,000 / 5,000 / 20,000), 40 small (100 / 200 / 500). A year of orders: 95 % paid, fulfilled and closed; 5 % cancelled.

**Workload** (`k6/mixed.js`, open model, rates × `SCALE`):
- browse 60/s (list, then 60 % open a product);
- search 30/s;
- full checkout 4/s (product, cart, item, checkout, shipping, completion);
- admin 10/s (orders, order detail, products, customers);
- developer feed 10/s.

40 % of the traffic goes to the large store. Every virtual user has its own client address, like shoppers behind a proxy.

**Objectives** (the run's thresholds): [docs/performance/slos.md](../docs/performance/slos.md).

**Profiling:** `load/tools/get-async-profiler.sh`, then:
- for a whole run: `JAVA_EXTRA="-agentpath:load/tools/async-profiler/lib/libasyncProfiler.so=start,event=cpu,file=/tmp/cpu.html" load/run-app.sh`;
- for a running app: `load/tools/async-profiler/bin/asprof -d 30 -e cpu -f flame.html <pid>`.

**Credentials:** `load/data/` and `load/results/` (gitignored) contain the load tenants' passwords, access tokens and API keys (k6 summaries include the tokens returned by `setup()`). They're valid only against the local `storeforge_load` database; don't copy them anywhere else. The secret scan flags them in a working-tree scan, which is expected; the CI scan covers the git history.

**Caveat:** app, database and load generator share one machine (12 cores); numbers are comparable between runs, not a statement of production capacity.
