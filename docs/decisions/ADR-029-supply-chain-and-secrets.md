# ADR-029: Supply chain gates (secrets, image vulnerabilities, SBOM), pinned third-party code, development secrets refused in deployments

- **Status:** Accepted
- **Date:** 2026-10-09
- **Phase:** P14
- **Evidence:** [EXP-022](../experiments/EXP-022-supply-chain-scan.md)

## Context
StoreForge ships 229 third-party components and a base image, plus the CI steps that build them. Nothing checked them until P14. The first scan found 47 HIGH/CRITICAL vulnerabilities and, on the way, an image that hadn't built since P8.

## Problem
1. How do vulnerable dependencies and leaked secrets get caught before they ship?
2. What runs in CI, and can a third party change it under us?
3. What stops a deployment from running with the public development secrets in `application.yml`?

## Options Considered
1. **Scanning:** OWASP Dependency-Check (slow, NVD-dependent); GitHub-hosted scanning; **Trivy on the built image (OS packages + every jar) and gitleaks on the full history, as pinned containers**.
2. **Pinning:** tags (`@v4`, `21-jre`); **commit SHAs for actions, exact versions for images, Dependabot to move them**.
3. **Development secrets:** warn; remove them (no zero-setup local run); **refuse to start with them when `ALLOW_DEVELOPMENT_SECRETS=false`**.

## Decision
1. **CI jobs:**
   - `secrets`: gitleaks over the full history, exceptions line-based with reasons in `.gitleaks.toml`;
   - `image`: build, then Trivy, failing on fixable HIGH/CRITICAL; accepted risks go in `.trivyignore` with reason and expiry (none today);
   - `verify`: also keeps the CycloneDX SBOM as an artifact. The SBOM is in the jar too, served at `/actuator/sbom` on the private port.
2. **Pinning:**
   - actions by commit SHA; scanners by exact image version, run as plain `docker run` (no third-party actions in the pipeline);
   - base images by exact Temurin version;
   - Dependabot for Maven (Spring Boot grouped), Docker and actions.
3. **Dependency fixes:**
   - Spring Boot 4.0.8, plus version overrides (Tomcat, Jackson 2 and 3, httpclient5/httpcore5) commented with why and when to remove them;
   - the unused `pebble` binary removed from the image.
4. `DevelopmentSecretsGuard`: with `ALLOW_DEVELOPMENT_SECRETS=false`, startup fails while any committed development secret is in use. It names the properties, never the values; a test keeps its list in sync with `application.yml`.
5. **Container:** non-root user; the Compose app runs read-only with tmpfs `/tmp`, all capabilities dropped and `no-new-privileges`.

## Why We Chose It
- Scanning the **image** checks what actually runs: OS packages and the exact jars, including transitive ones.
- Pinning makes third-party changes deliberate pull requests that CI tests, instead of silent changes on the next run.
- Failing at startup is the only control that can't be ignored. A WARN in a log isn't one.

## Trade-offs
- The gate fails builds on new CVEs in unchanged code. That's intended: someone decides (upgrade or a documented, expiring exception).
- Version overrides drift from Spring Boot's tested set; each is removed once Boot catches up.
- `--ignore-unfixed` lets unfixable findings through, reported but not blocking.

## Consequences
- P16's deployment checklist sets `ALLOW_DEVELOPMENT_SECRETS=false`, `AUTH_ALLOW_EPHEMERAL_SIGNING_KEY=false` and `TRUSTED_PROXIES`.
- Allow-list entries are reviewed like code: EXP-022 showed how a path-based exception silently exempted whole files.
