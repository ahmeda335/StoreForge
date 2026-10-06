# Health Probes and Graceful Shutdown

**Problem.** Load balancers and orchestrators need to know (a) whether to send traffic to an instance and (b) whether to kill and restart it. One generic `/health` that includes the database answers both questions the same way. During a DB outage it would make the orchestrator restart **every** instance repeatedly (a restart storm), which adds startup load at the worst moment and fixes nothing.

**Why we need it.** Every deployment target from P16 on (Compose healthchecks, cloud LBs, Kubernetes) uses these signals.

**Alternatives.** A single health endpoint; TCP port checks only (a process can listen while being useless).

**Decision.**
| Probe | Question | Includes | On failure |
|---|---|---|---|
| Liveness `/actuator/health/liveness` | Is the process broken beyond self-repair (deadlock, unrecoverable state)? | `livenessState` only | Restart |
| Readiness `/actuator/health/readiness` | Can it serve requests right now? | `readinessState` + `db` | Stop routing traffic, don't restart |

**Mental model.** Spring Boot tracks `LivenessState` and `ReadinessState` as application events. At startup, readiness becomes ACCEPTING_TRAFFIC only after the context is fully started. On SIGTERM: graceful shutdown begins, readiness goes REFUSING_TRAFFIC, the server stops accepting new connections and waits (up to `timeout-per-shutdown-phase`) for in-flight requests, then beans (connection pool) close.

**Project usage.** `management.endpoint.health.group.{liveness,readiness}` in `application.yml`; management port 8081; `server.shutdown=graceful`.

**Failure cases.** Probe timeout shorter than the DB check (3s pool timeout during outages, see [EXP-001](../experiments/EXP-001-database-unavailable-p1.md)); readiness depending on non-critical dependencies (e.g. email provider): never include those, or a degraded feature takes the whole service out.

**Testing.** `OperationalEndpointsIT` (liveness excludes DB, readiness includes DB); EXP-001 (outage and recovery, graceful shutdown).

**Observability.** The probe results themselves; later, alerts on readiness flapping and restart counts.

**When NOT to use it.** Don't add business checks ("are there orders?") to probes. Probes answer "can this instance work?", not "is the business OK?" (that's monitoring).
