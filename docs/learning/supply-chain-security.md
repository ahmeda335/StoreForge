# Supply Chain Security: Dependencies, Images, CI and Secrets

**Problem.** Most code that runs is someone else's: 229 libraries, a base image, CI actions. A vulnerability or a compromise in any of them is ours; so is a secret committed by accident.

**Why we need it.** Nothing checked them before P14 (EXP-022: 47 HIGH/CRITICAL).

**Alternatives.**
- **Periodic manual review:** forgotten.
- **Registry or GitHub-native scanning:** alerts, not gates.
- **Gates in CI:** builds fail on fixable HIGH/CRITICAL and on secrets.
- **SBOMs:** know what you ship.

**Decision.** gitleaks + Trivy as CI gates, a CycloneDX SBOM per build, everything third-party pinned, Dependabot to move pins, development secrets refused at startup ([ADR-029](../decisions/ADR-029-supply-chain-and-secrets.md)).

**Mental model.**
- **Scan what runs:** the built image includes OS packages and the exact transitive jars. A `pom.xml` scan misses what the base image brings.
- **Fix at the right level:** upgrade the platform BOM (Spring Boot) first, override single versions only when needed, and say when to remove each override.
- **Remove instead of ignore:** an unused binary (pebble) is attack surface; delete it.
- **Pin by content:** a tag (`@v4`, `21-jre`) can move under you, or point at a stale local copy. Commit SHAs and exact versions don't; Dependabot proposes the moves and CI tests them.
- **Exceptions are code:** each needs a reason, ideally an expiry, and a test that it doesn't hide more than intended (EXP-022's path-based gitleaks exception hid a planted token).
- **Fail closed on configuration:** a warning is not a control.

**Project usage.**
- `.github/workflows/ci.yml` jobs `secrets` and `image`; `.gitleaks.toml`; `.trivyignore`; `.github/dependabot.yml`;
- the overrides in `pom.xml`;
- `DevelopmentSecretsGuard`; `/actuator/sbom`.

**Failure cases.**
- Alert-only scanning;
- ignoring instead of fixing;
- floating tags;
- third-party scanner actions running with repository access (themselves a supply-chain target);
- exceptions that exempt whole files.

**Testing.**
- Planted secrets in a scratch directory must be caught.
- The CI gate command run locally (exit 0).
- The hardened container smoke-tested (read-only, non-root).
- The startup refusal tested in a container.

**Observability.** CI results; the SBOM artifact per build ("are we affected by CVE-X?"); Dependabot pull requests.

**When NOT to use it.** Never skip it, but don't block on unfixable findings either: report them, track them, and fix them when a fix exists.
