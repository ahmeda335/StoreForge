# ADR-030: Keep virtual threads, but bound the work in progress and never block inside a monitor

- **Status:** Accepted
- **Date:** 2026-10-09
- **Phase:** P15
- **Evidence:** [EXP-023](../experiments/EXP-023-load-baseline.md)

## Context
Since P1, requests run on virtual threads (`spring.threads.virtual.enabled`). Under 5× the reference load the application deadlocked and, once that was fixed, filled its heap and died in garbage collection. Two properties of virtual threads caused it:
1. **No natural bound.** A platform thread pool (200) capped work in progress; virtual threads start every accepted request at once, so overload became an ever-growing queue in memory.
2. **Pinning on JDK 21.** A virtual thread that blocks inside a `synchronized` section keeps its carrier; with all carriers pinned, nothing runs.

## Problem
How do we keep the simplicity of blocking code on virtual threads without unbounded queues or carrier starvation?

## Options Considered
1. **Back to a platform thread pool:** a bound again, but threads are expensive and a pool is a hidden queue too.
2. **Upgrade to JDK 24+ / 25 LTS** (JEP 491: `synchronized` no longer pins): removes pinning, not the missing bound.
3. **Keep virtual threads; add an explicit concurrency limit (load shedding); remove blocking I/O from monitors; detect pinning in load runs.**

## Decision
Option 3:
- `ConcurrencyLimitFilter`: at most 64 requests in flight per instance (a few times the DB pool), a 50 ms wait for a slot, then `503 overloaded` + `Retry-After: 1`. The management port is not limited, so health stays responsive.
- No blocking I/O inside `synchronized` or inside a cache's compute. Caches load outside (`getIfPresent`, load, `put`); locks around I/O are `ReentrantLock`.
- Load runs use `-Djdk.tracePinnedThreads=short` to find remaining pinning (known: JavaMail's SMTP client, only in the email job).
- **JDK 25** is recommended as a separate upgrade (also for the JavaMail case); it doesn't replace the limit.

## Why We Chose It
- **Measured:** at 5× load, 252 requests/s and a dead instance became 754 requests/s with fast 503s and a healthy instance; at 3×, the limit cut p95 from seconds to 200–400 ms.
- **Explicit beats implicit:** the bound is configured, named, measured (`in_flight`, `shed_total`) and documented.
- **Fast rejections are cheap**, and clients and load balancers know what to do with 503 + Retry-After. Slow timeouts cost everyone.

## Trade-offs
- Under overload some requests fail fast instead of all requests being slow.
- The limit is per instance and static. An adaptive limit (e.g. gradient/AIMD on latency) could follow load more closely; not built.
- The right value depends on the pool and query costs: revisit when either changes.

## Consequences
- Capacity planning in terms of the database: the pool size and per-request DB time decide throughput; the limit decides how much queueing is allowed.
- New caches with I/O loaders must load outside the compute, and new locks around I/O must be `ReentrantLock`, at least until JDK 25.
