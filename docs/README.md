# StoreForge Documentation

Documentation is part of the implementation: every phase updates the sections it touches.

| Folder | Contains | Updated when |
|---|---|---|
| [blueprint/](blueprint/README.md) | The engineering plan (P0): architecture, roadmap, learning map | Only via an ADR when a decision changes |
| [PROGRESS.md](PROGRESS.md) | Phase status, what's built, known limitations | End of every phase |
| [architecture/](architecture/overview.md) | **As-built** system: components, module boundaries, flows (Mermaid) | Every phase |
| [decisions/](decisions/README.md) | ADRs | Whenever a significant decision is made |
| [domains/](domains/README.md) | One page per business module: model, invariants, API, events | When a module is built |
| [database/](database/README.md) | Roles, schema conventions, migrations, locking decisions | Schema changes |
| [api/](api/conventions.md) | API conventions, error format, endpoints | API changes |
| [security/](security/README.md) | Security posture per phase, threat notes | Every phase |
| [events/](events/README.md) | Event catalog, delivery semantics | From P9 |
| [infrastructure/](infrastructure/local-development.md) | Local setup, Docker, CI, later deployment | Infra changes |
| [observability/](observability/README.md) | What we observe, why, where the signal comes from, how to investigate | Every phase |
| [testing/](testing/README.md) | Test levels, how to run, load-test results | Every phase |
| [operations/](operations/README.md) | Runbooks for alerts and incidents | When a failure mode is understood |
| [experiments/](experiments/README.md) | Failure experiments: expected vs. actual | When experiments are run |
| [learning/](learning/README.md) | Technology and pattern explainers, taught through this project | When a technology or pattern is introduced |
| [troubleshooting/](troubleshooting/README.md) | Known problems and fixes for developers | As problems are hit |

`experiments/` was added to the suggested structure because the prompt treats experiments as first-class deliverables with a fixed write-up format.
