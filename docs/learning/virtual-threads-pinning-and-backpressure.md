# Virtual Threads: Pinning and Backpressure

**Problem.** Virtual threads make blocking code cheap: one per request, thousands at once. Two things a platform thread pool did implicitly stop happening:
- **bounding work in progress;**
- **never running out of carriers:** a blocked platform thread doesn't stop other threads from running.

**Why we need it.** EXP-023: 5× load deadlocked the application, then (deadlock fixed) filled the heap.

**Alternatives.**
- **Platform thread pools:** bounded, but expensive and an invisible queue.
- **Reactive code:** explicit backpressure, but a different programming model.
- **Virtual threads + an explicit concurrency limit + no blocking under monitors.**

**Decision.** The third ([ADR-030](../decisions/ADR-030-bounded-concurrency-and-virtual-threads.md)).

**Mental model.**
- A virtual thread runs on a **carrier** (one per core by default). Blocking I/O normally *unmounts* it, freeing the carrier.
- **Pinning (JDK 21–23):** inside `synchronized` (or native code) the virtual thread can't unmount, so blocking there blocks the carrier. With all carriers pinned, *no* virtual thread runs, including the ones that would release what the pinned ones wait for: deadlock. JDK 24+ (JEP 491) removes pinning for `synchronized`.
- **Hidden monitors:**
  - `ConcurrentHashMap.compute`, which underlies Caffeine's `cache.get(key, loader)`, is `synchronized` per bin;
  - legacy libraries (JavaMail);
  - your own `synchronized` around I/O.
- **Backpressure:** without a bound, overload becomes memory. Every waiting request keeps its buffers and its stack. Shed load early and cheaply (503 + Retry-After); keep in-flight work near what the bottleneck (the DB pool) can actually do.
- Health endpoints must stay outside the limit (separate management port), or overload looks like death.

**Project usage.** `ConcurrencyLimitFilter`; `CachingStoreDirectory` (load outside the cache); `ReentrantLock` in `RedisConnections` and the rate limiters; `-Djdk.tracePinnedThreads=short` in load runs.

**Failure cases.**
- Blocking loaders in caches;
- `synchronized` around JDBC/HTTP/Redis calls;
- "virtual threads mean we don't need limits";
- thread dumps without virtual threads (`jstack` doesn't show them: use `jcmd <pid> Thread.dump_to_file`).

**Testing.** `CachingStoreDirectoryTest` (a slow lookup must not block another request for the same key), `ConcurrencyLimitFilterTest` (fast 503, slot released); load runs with pinning traces.

**Observability.**
- `storeforge_http_requests_in_flight`, `storeforge_http_requests_shed_total`;
- `jvm_gc_pause_seconds`;
- a heap histogram full of request objects means an unbounded queue;
- carrier threads all "Carrying virtual thread" inside a monitor means pinning.

**When NOT to use it.** CPU-bound work gains nothing from virtual threads. Code that must hold locks across I/O on JDK 21 should use `ReentrantLock`, or wait for JDK 24+.
