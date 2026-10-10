# Failure experiments (staging only)

Scripted fault injection under steady traffic (P17, [EXP-025](../../docs/experiments/EXP-025-failure-suite-on-staging.md)). **Never point this at production.**

## Setup
```bash
# Staging with the overlay: the app reaches PostgreSQL and Redis through Toxiproxy (API on 127.0.0.1:18474).
COMPOSE_EXTRA=chaos/compose.chaos.yaml deploy/deploy.sh staging <image>
# A tenant for the experiments (owner, store, product with deep stock) -> deploy/state/staging/chaos.json
python3 deploy/chaos/setup.py --env staging
```
Deploy staging with the overlay every time. A plain deploy removes it, and `run.sh`'s latency faults would then have nothing to act on.

## Run
```bash
deploy/chaos/run.sh <experiment> [fault seconds, default 120]
```
| Experiment | Fault |
|---|---|
| `pg-kill` | PostgreSQL killed (SIGKILL), started again at heal |
| `pg-latency` | +200 ms ± 50 ms on every PostgreSQL response (Toxiproxy) |
| `redis-kill` | Redis killed |
| `lock` | `store_counters` locked exclusively for the fault time (blocks order numbers) |
| `worker-kill` | Worker killed with SIGKILL |
| `storage-down` | SeaweedFS stopped; run `python3 deploy/chaos/upload.py` during the fault (the steady traffic doesn't upload) |
| `noisy-tenant` | One client in the environment's network sends 200 searches/s to the chaos store |

Each run has three phases:
- 60 s of steady traffic ([traffic.js](traffic.js): browse 3/s and 6 checkouts/min, under every per-address limit);
- the fault, then heal;
- 180 s of steady traffic.

The report goes to `deploy/state/staging/chaos/<time>-<experiment>/` (gitignored):
- `report.txt`: failures by phase, kind and step; time to the last failure after heal; orders placed; alerts active; **invariant violations** ([invariants.sql](invariants.sql), every row is a violation);
- `points.json`: raw k6 points;
- `timeline.txt`.

## Abort
Ctrl-C (or SIGTERM) heals first, then removes the traffic containers. Healing twice is harmless.

Stop an experiment early when:
- invariants show violations;
- the stack doesn't recover within a few minutes of heal;
- something outside the staging project is affected.

## Notes
- Steady-state numbers depend on the laptop: compare runs on the same machine, and repeat any surprising run.
- Alert results depend on the previous hour or six hours (rule windows). Back-to-back runs share that history.
- Toxiproxy by hand: `curl -s localhost:18474/proxies`, `curl -X DELETE localhost:18474/proxies/postgres/toxics/<name>`.
