# 27 — Prerequisite Learning Map

Legend: 🔴 **Must understand before implementation** · 🟡 **Can learn while implementing** · 🔵 **Advanced / study later**

Depth scale: *Aware* (know it exists and why) → *Working* (use it correctly) → *Deep* (explain internals, debug failures).

| Concept | Why you need it | Understand first | Depth | Used in | Mark |
|---|---|---|---|---|---|
| HTTP semantics | Every API | Methods, status codes, headers, idempotent vs. safe methods | Working | P1+ | 🔴 |
| SQL & relational modelling | Data correctness | Joins, normalization, keys | Working | P1+ | 🔴 |
| Database transactions & ACID | Atomicity of business operations | What commit/rollback guarantee | Deep | P2+ | 🔴 |
| PG isolation levels & MVCC | Understanding races | Transactions | Deep | P5, P7, P8 | 🔴 before P5 |
| PostgreSQL locking (row locks, `FOR UPDATE`, `SKIP LOCKED`, deadlocks) | Inventory, queues | MVCC | Deep | P5, P9 | 🔴 before P5 |
| Spring DI & `@Transactional` proxies | Transaction boundaries actually work | OOP, proxies (self-invocation pitfall) | Working | P1+ | 🔴 |
| JPA persistence context | Avoid N+1, lazy loading bugs | SQL | Working | P2+ | 🟡 |
| Docker basics | Local env | Processes, ports, filesystems | Working | P1 | 🔴 |
| Testcontainers | Real integration tests | Docker | Working | P1 | 🟡 |
| Password hashing | Credential storage | Hash vs. encryption, salts, work factors | Working | P2 | 🔴 before P2 |
| JWT | Access tokens | Base64url, signatures (HMAC vs. ECDSA), claims | Working (RFC 8725 pitfalls) | P2 | 🔴 before P2 |
| Spring Security filter chain | Where auth runs | Servlet filters | Working | P2 | 🔴 before P2 |
| Cookies, CORS, CSRF | Browser security | HTTP | Working | P2, F1 | 🔴 before P2 |
| Multi-tenancy models | Core architecture | Transactions, DB roles | Deep | P3+ | 🔴 before P3 |
| PostgreSQL RLS & roles | Tenant isolation | SQL privileges | Working | P3 | 🔴 before P3 |
| RBAC | Authorization | — | Working | P3 | 🔴 before P3 |
| Keyset pagination | Lists at scale | Indexes | Working | P4 | 🟡 |
| Optimistic locking | Concurrent edits | Lost update problem | Working | P4 | 🟡 |
| Idempotency | Retries and duplicates are normal | HTTP semantics, unique constraints | Deep | P7+ | 🔴 before P7 |
| State machines | Orders, payments | Enums, invariants | Working | P8, P10 | 🟡 |
| Distributed tracing | See async flows | Spans, context | Working | P6+ | 🔴 before P6 |
| OpenTelemetry | Telemetry standard | Tracing concepts | Working | P6 | 🟡 |
| Prometheus metric types | Correct metrics | Counters/gauges/histograms, labels | Working | P1, P6 | 🟡 |
| Eventual consistency | Async reactions | Transactions | Working | P9+ | 🔴 before P9 |
| Message queues (concepts) | Async jobs | At-least-once, acks, visibility timeout | Working | P9 | 🔴 before P9 |
| Transactional outbox | Reliable events | Dual-write problem | Deep | P9 | 🔴 before P9 |
| Retries, backoff, jitter | Every external call | Idempotency | Working | P9+ | 🔴 before P9 |
| Payment flows (auth/capture) | Payments | Idempotency, state machines | Working | P10 | 🔴 before P10 |
| HMAC signatures | Webhooks | Hashes, MACs | Working | P10, P11 | 🔴 before P10 |
| SSRF | Outbound webhooks | Networking, DNS | Working | P11 | 🔴 before P11 |
| OAuth 2.0 + PKCE | Developer platform | HTTP redirects, tokens | Deep | P12 | 🔴 before P12 |
| Rate limiting algorithms | Fairness, abuse | Counters, time windows | Working | P2 (simple), P12 | 🟡 |
| Redis | Shared state | Data structures, TTL, atomicity | Working | P12 | 🟡 |
| Circuit breakers / bulkheads | Resilience | Timeouts, retries | Working | P10+ | 🟡 |
| Caching strategies | Performance | Consistency trade-offs | Working | P13 | 🟡 |
| Full-text search | Catalog search | Inverted indexes | Working | P13 | 🟡 |
| SLIs/SLOs/error budgets | Reliability targets | Metrics, percentiles | Working | P15 | 🟡 |
| Load testing methodology | Performance work | Percentiles, open vs. closed models | Working | P15 | 🟡 |
| Profiling (JFR, async-profiler) | CPU/alloc hot spots | JVM basics | Working | P15 | 🟡 |
| TLS, DNS, reverse proxies | Production | Networking | Working | P16 | 🟡 |
| Load balancing | Multiple instances | Health checks, sticky vs. stateless | Working | P16 | 🟡 |
| Zero-downtime migrations | Deploys | Migrations, rolling deploys | Working | P16 | 🟡 |
| Kafka | Event streaming | Queues, logs, partitions | Working | P18 | 🔵 |
| Kubernetes | Orchestration | Docker, networking | Working | P18 | 🔵 |
| Sharding / Citus | Horizontal scale | Multi-tenancy, distributed transactions | Aware → Working | P18 | 🔵 |
| CDC (Debezium) | Event streams from DB | Kafka, WAL | Aware | P18 | 🔵 |
| Consensus/replication internals | Understanding managed DB failover | — | Aware | P16+ | 🔵 |

## Learning chains (study in this order)

### Transactional outbox
```text
Database transactions (what COMMIT guarantees)
  ↓ ACID; especially atomicity & durability
  ↓ The dual-write problem: DB + queue/broker cannot commit atomically (crash between them = lost or phantom event)
  ↓ At-least-once delivery & why consumers must be idempotent
  ↓ SELECT ... FOR UPDATE SKIP LOCKED (multiple relays without double processing)
  ↓ Outbox pattern (write event row in the same TX; relay publishes later)
  ↓ Implement (P9) → break it (kill relay mid-batch) → observe duplicates handled
```

### Multi-tenancy
```text
SQL WHERE clauses & indexes → DB roles & privileges → RLS policies (USING / WITH CHECK, FORCE)
  ↓ Connection pooling: session state leaks between requests → SET LOCAL (transaction-scoped)
  ↓ Request-scoped context in Spring
  ↓ Tenancy models trade-offs (04-multi-tenancy.md)
  ↓ Implement (P3) → write the A/B isolation suite → try to break it
```

### Inventory concurrency / PostgreSQL locking
```text
Transactions → MVCC (each TX sees a snapshot) → lost update example (read-modify-write)
  ↓ Row-level locks (UPDATE takes them; FOR UPDATE takes them early)
  ↓ READ COMMITTED re-check behaviour on UPDATE (PG re-evaluates WHERE on the newest row version)
  ↓ Deadlocks & lock ordering
  ↓ Conditional update pattern + CHECK constraints
  ↓ Implement (P5) → 100-thread test → compare with SERIALIZABLE
```

### JWT & OAuth2
```text
Hashing vs. MAC vs. signatures → JWT structure → JWT validation rules (alg pinning, exp, aud, iss, kid)
  ↓ Access vs. refresh tokens; rotation; revocation trade-offs
  ↓ OAuth2 roles (resource owner, client, AS, RS) → authorization code flow → PKCE → scopes → consent
  ↓ OAuth Security BCP (RFC 9700)
```

### Idempotency
```text
HTTP method semantics (idempotent ≠ safe) → why retries happen (timeouts, unknown outcome)
  ↓ Unique constraints as dedup → idempotency keys (store request hash + response)
  ↓ Concurrent same-key requests (in-progress state, 409)
  ↓ Idempotency toward providers (forward a stable key)
```

### Distributed tracing / OpenTelemetry
```text
Logs vs. metrics vs. traces → trace/span model → context propagation (W3C traceparent)
  ↓ Instrumentation (auto vs. manual) → sampling (head vs. tail)
  ↓ OTel architecture: API, SDK, exporter, OTLP, Collector
  ↓ Propagation across async boundaries (store context with the message)
```

### Redis & rate limiting
```text
Why per-instance limits fail behind a load balancer → token bucket vs. sliding window
  ↓ Redis atomic ops / Lua → TTL → Redis failure modes (fail-open vs. fail-closed decision)
```

### Circuit breakers & resilience
```text
Timeouts (always first) → retries + backoff + jitter → retry amplification
  ↓ Circuit breaker states (closed/open/half-open) → bulkheads → fallbacks (and when they're dangerous)
```

### Observability → SLOs
```text
Metric types → percentiles & histograms → RED/USE methods → SLIs → SLOs → error budgets → burn-rate alerts
```

### Docker → Kubernetes
```text
Processes & networking → images/containers/volumes → Compose → health checks & graceful shutdown
  ↓ (only later) Pods, Deployments, Services, Ingress, probes, resources, HPA
```

### Kafka (later)
```text
Queue semantics (RabbitMQ-style) → log semantics (offsets, retention) → partitions & ordering keys
  ↓ Consumer groups & rebalancing → exactly-once myths → when a log beats a queue
```

Suggested resources: PostgreSQL docs (Ch. 13 Concurrency Control, Ch. 5.9 RLS); *Designing Data-Intensive Applications* (Kleppmann) chapters 5, 7, 11; Google SRE Book & Workbook (SLO chapters); OWASP API Security Top 10; RFC 6749, 7636, 8725, 9457, 9700; OpenTelemetry docs "Concepts"; Spring Security reference "Architecture"; microservices.io (outbox, saga) by Chris Richardson.
