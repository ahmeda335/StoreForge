# EXP-022: First supply chain scan: secrets in history, vulnerable dependencies, and what the scan found on the way

- **Date:** 2026-10-09 · **Phase:** P14
- **Setup:**
  - gitleaks 8.30.1 (full git history, 63 commits, and the working tree);
  - Trivy 0.74.0 on the Docker image (OS packages + every jar), severity HIGH/CRITICAL;
  - a scratch directory with planted secrets to test the exceptions.

## Experiment
1. Scan, triage every finding, fix or document it.
2. Check the scanners still catch real secrets after adding exceptions.
3. Build the image as CI does, scan it, run it hardened.

## Expected behavior
A few findings to triage; the image builds.

## Actual behavior

| Step | Observed |
|---|---|
| gitleaks history | 4 findings: two idempotency-key UUIDs in `.http` files, a fake Stripe fixture, the committed development webhook key |
| First `.gitleaks.toml` | Repository clean, **but a planted GitHub token in an `.http` file went unnoticed** |
| `docker build` | **Failed: the image had not built since P8** (`lombok.config` not copied) |
| Trivy (Boot 4.0.6) | **47 HIGH/CRITICAL**: Tomcat (7 critical), Spring MVC (2 critical), Jackson 2/3, Netty, PostgreSQL driver, Micrometer, httpcore5, spring-data; Go stdlib in `/usr/bin/pebble` |
| Boot 4.0.8 | Most fixed; Tomcat 11.0.24, Jackson 3.1.5/2.21.5, httpcore5 5.3.6 still vulnerable |
| + overrides, pinned base image | 2 (pebble) |
| pebble removed | **0**; the CI gate command exits 0 |
| Hardened container | Ready; uid 999, read-only filesystem, no capabilities |
| `ALLOW_DEVELOPMENT_SECRETS=false` | Exit 1; names 4 properties, no values in the log |

## What failed / why
- **Exceptions:**
  - gitleaks allow-lists with both `paths` and `regexes` match on either (OR), so "idempotency keys in `.http` files" exempted every `.http` file;
  - allow-list regexes are compared with the extracted secret, not the line, unless `regexTarget = "line"`;
  - even with `condition = "AND"` and `regexTarget = "line"` the planted token stayed hidden; I did not find out why;
  - so the final exceptions use no paths at all: line-based regexes only. Planted secrets are caught.
- **The image build** was never checked locally after the Lombok change: tests run from the repo root, where `lombok.config` exists.
- **Vulnerabilities:** the dependency set was the one P1 chose, never revisited. The floating base tag `21-jre` used a stale cached image.
- **Development secrets:** they only produced a warning.

## How it was detected
The scanners, and building the image to scan it.

## How the system recovered
N/A.

## What we changed
- Dependencies: Boot 4.0.8 plus documented overrides.
- Base image pinned, `pebble` removed, `COPY lombok.config` in the Dockerfile.
- `DevelopmentSecretsGuard`.
- Line-based gitleaks exceptions.
- CI jobs `secrets` and `image`, the SBOM artifact, Dependabot, actions pinned by SHA.
- [ADR-029](../decisions/ADR-029-supply-chain-and-secrets.md).
- **Lesson:** an exception list is code. Test it with planted positives, or it may hide more than intended.
