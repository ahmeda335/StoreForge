# EXP-023: Load baseline, the overload cliff, and four measured improvements

- **Date:** 2026-10-09 · **Phase:** P15
- **Setup:**
  - app jar (1 GB heap, G1, virtual threads, pool 10) against `storeforge_load` in Compose PostgreSQL 17 (`pg_stat_statements` on), plus Redis, SeaweedFS and Mailpit;
  - k6 2.3.0 open-model mix ([load/README](../../load/README.md)): 50 stores, ~400,000 orders, SCALE 1 ≈ 170 requests/s;
  - everything on one 12-core laptop shared with an IDE and a browser (load average 7–13 during runs).
- **Caveat:** absolute numbers drift with the machine's other load, so comparisons are paired runs a few minutes apart.

## Experiment
1. Baseline at SCALE 1; capacity steps at SCALE 3, 5 (8 was aborted after 5 collapsed).
2. Diagnose each failure (heap histogram, virtual-thread dumps, carrier threads, `pg_stat_statements`, `EXPLAIN` as the app role), fix, and measure again under the same load.

## Expected behavior
Latency grows with load until the objectives are missed; beyond that, errors grow gradually.

## Actual behavior

| Run | Requests/s | Storefront p95 / p99 | Checkout p95 | Admin p95 | Failed | Dropped |
|---|---|---|---|---|---|---|
| Baseline SCALE 1 | 173 | 43 / 76 ms | 26 ms | 16 ms | 0 % | 0 |
| SCALE 3 (first) | 516 | 80 / 222 ms | 35 ms | 24 ms | 0 % | 0 |
| **SCALE 5 (first)** | **252** | **60 s timeouts** | **60 s** | **55 s** | **90 %** | **36,358** |

**SCALE 5 never recovered:** the app stopped answering everything, including health on the management port, minutes after the load stopped.

### Finding 1: deadlock between virtual threads, a cache and the connection pool
- **Heap:** 977 of 1,024 MB, with ~350,000 Tomcat request objects and 20,000 virtual-thread stacks.
- **Thread dump:** 7,083 virtual threads parked on Redis futures that never completed; Redis and Lettuce were idle. **All 12 carrier threads** carried virtual threads blocked in `ConcurrentHashMap.compute`, inside Caffeine's `cache.get(host, loader)` in `CachingStoreDirectory`.
- **Mechanism:**
  1. The loader, a blocking JDBC query, ran inside a `synchronized` section.
  2. On JDK 21 that pins the carrier, and so does every request waiting for the same host.
  3. The loader needed a pooled connection held by parked threads, and those needed a carrier to continue.
  4. Nothing could move. It starts when a busy host's 60 s cache entry expires under load.
- **Fix:** load outside the cache (`getIfPresent`, query, `put`); `ReentrantLock` instead of `synchronized` around Redis connects. Regression test: `CachingStoreDirectoryTest` (a slow lookup doesn't block another for the same host). `-Djdk.tracePinnedThreads=short` found the remaining monitors: JavaMail's SMTP client (email job only) and the limiter's lazy Redis setup (fixed).

### Finding 2: unbounded concurrency fills the heap
With the deadlock fixed, SCALE 5 still died, differently: 272 requests/s, 94 % failed, **1,432 full GCs (580 s of GC)**. With virtual threads every accepted request starts at once; nothing bounded the work in progress (a 200-thread pool used to).

| SCALE 5 | Throughput | Failed | Dropped | Full GCs | Health afterwards |
|---|---|---|---|---|---|
| Deadlock fixed only | 272/s | 94 % | 34,032 | 1,432 | timeout |
| **+ load shedding (256 in flight)** | **754/s** | 56 % (fast 503 + Retry-After) | **0** | **0** | **200 in 14 ms** |

The limit then tuned (SCALE 3, paired runs, pool 10):

| In-flight limit | Storefront p95 | Admin p95 | Developer p95 | Checkout p95 | Shed/failed | Dropped |
|---|---|---|---|---|---|---|
| none | 3,029 ms | 5,825 ms | 7,092 ms | 2,166 ms | 5.1 % | 861 |
| 256 | 754 ms | 1,157 ms | 1,699 ms | 556 ms | 5.8 % | 9 |
| **64 (default)** | **224 ms** | **328 ms** | **423 ms** | **201 ms** | 6.0 % | 0 |

With 10 connections and 10–30 ms of database time per request, useful concurrency is a few dozen (Little's law); everything above that only queues.

### Finding 3: citext lookups can't use an index under RLS
The storefront's product-by-handle lookup (`handle = :handle`) was a sequential scan over all 42,000 products of every store:
- **The binding:** `citext = text` resolves to text comparison, so the citext unique index can't be used.
- **Casting the parameter to citext was slower** (11.6 ms, still a scan): `citext_eq` is not LEAKPROOF, so under RLS it can never be an index condition (the EXP-018 lesson in a new place).
- **The fix:** handles are lowercase by constraint, so V20 adds an index on `(store_id, handle::text)` and the query compares text with a lowercased parameter.
- **Also fixed:** `users` and `store_domains` have no RLS, so their lookups cast to citext and use their indexes (login's derived query bound a varchar).
- **Guard:** `CitextComparisonTest` fails on any uncast comparison of a citext column.

| Product by handle | Plan | Mean (SCALE 3) |
|---|---|---|
| `handle = :handle` (text) | seq scan, 41,999 rows removed | 3.67 ms |
| `handle = CAST(:handle AS citext)` | seq scan (citext_eq not leakproof) | 11.59 ms |
| **`handle::text = :lowercased` + V20 index** | index scan | **0.05 ms** |

### Finding 4: search normalized every candidate title, twice
`lower(unaccent(title))` ran 8.8 million times in 2 minutes: once for the trigram recheck and once for the similarity score. V21 stores it (`title_search`, generated) and indexes that.

| Search (SCALE 3) | Before | After |
|---|---|---|
| Match function, mean | 37.5 ms | **13.7 ms** |
| `unaccent` calls | 8.8 M | **0** |
| Connection-seconds in 2 min | 934 | **662** |

### Not an improvement: a bigger pool
With pool 20, statements got slightly slower and acquire waits stayed (connection use 1,026 s): PostgreSQL is CPU-bound on a saturated machine. Pool 10 stays.

### Final configuration
| Run | Requests/s | Storefront p95 / p99 | Checkout p95 | Admin p95 | Developer p95 | Failed |
|---|---|---|---|---|---|---|
| SCALE 1 | 173 | 36 / 63 ms | 28 ms | 17 ms | 16 ms | 0 % |
| SCALE 2 | 341 | 201 / 346 ms | 250 ms | 297 ms | 392 ms | 1.3 % (shed) |

On this machine, sustainable capacity within all objectives is about SCALE 1.5–2 (≈ 300 requests/s). Beyond it, the service **sheds load and stays healthy** instead of collapsing.

## How it was detected
- k6 thresholds failing; dropped iterations;
- `jcmd GC.heap_info` / `GC.class_histogram` / `Thread.dump_to_file`; `jstack` for carrier threads; `jstat -gcutil`;
- `-Djdk.tracePinnedThreads`;
- `pg_stat_statements` per run, then `EXPLAIN (ANALYZE)` as the app role with a tenant set.

## How the system recovered
Before the fixes: it didn't. It needed a restart (`kill -9`).

## What we changed
- `CachingStoreDirectory` (load outside the cache); `ReentrantLock` for Redis connects.
- `ConcurrencyLimitFilter` (64 in flight, 50 ms wait, then 503 `overloaded` + Retry-After; metrics `storeforge_http_requests_in_flight`, `storeforge_http_requests_shed_total`).
- V20 (handle text index), V21 (stored normalized title).
- Citext casts where there's no RLS, and `CitextComparisonTest`.
- Object storage no longer blocks startup (found while setting up the runs).
- [ADR-030](../decisions/ADR-030-bounded-concurrency-and-virtual-threads.md).
