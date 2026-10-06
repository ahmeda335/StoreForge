# Spring Modulith

**Problem.** In a monolith, nothing stops `checkout` from calling `inventory`'s repository directly or `orders` and `payments` from depending on each other in a cycle. Boundaries erode one "quick fix" at a time until extraction (or understanding) is impossible.

**Why we need it.** ADR-001 bets on a modular monolith. That bet only pays off if the modules stay modules.

**Alternatives.** Maven multi-module (compile-time enforcement, more build overhead and ceremony); ArchUnit rules written by hand (flexible, more work); discipline alone (doesn't scale, even for one person).

**Decision.** Spring Modulith's `ApplicationModules.verify()` in `ModularityTest`, run on every build.

**Mental model.** Each direct sub-package of the application's root package (`dev.storeforge.<module>`) is a module. Types in the module's root package are its **API**; types in sub-packages are **internal** unless explicitly exposed (named interfaces) or the module is declared `OPEN`. `verify()` fails if any module uses another's internals or if module dependencies form a cycle.

**Project usage.** `ModularityTest`; `shared/package-info.java` declares the shared kernel `OPEN` so `shared.web` etc. are usable everywhere. Business modules (from P2) will be closed: their `internal` packages are private.

**Configuration.** `spring-modulith-api` (annotations) at compile scope; `spring-modulith-starter-test` at test scope; version via `spring-modulith-bom`.

**Failure cases.** Over-using `OPEN` turns the check off. Only the kernel is open. A growing `shared` module becomes a dumping ground: keep business logic out of it.

**Testing.** It is a test. Try it: reference `dev.storeforge.x.internal.Something` from another module and watch the build fail.

**Observability.** Later, Modulith can generate module diagrams (`Documenter`) and observe module-to-module calls in traces.

**When NOT to use it.** Tiny apps with one domain area.
