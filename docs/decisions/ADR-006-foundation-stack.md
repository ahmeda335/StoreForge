# ADR-006: Foundation stack: Java 21, Spring Boot 4, Maven, Flyway, separate DB roles

- **Status:** Accepted
- **Date:** 2026-10-06
- **Phase:** P1

## Context
Phase 1 creates the skeleton every later phase builds on. Choices made here are expensive to change later.

## Problem
Which runtime, framework version, build tool, migration tool and database access setup do we start with?

## Options Considered
- **Spring Boot 3.5 vs. 4.0.** 3.5 has more blog posts and answers online. 4.0 is the current generation (Spring Framework 7, Jackson 3, JSpecify null-safety, modular auto-configuration, first-class OpenTelemetry starter). 4.1 exists but 4.0.x is the established line.
- **Maven vs. Gradle.** Gradle is faster and more flexible. Maven is declarative and has the clearest surefire (unit) / failsafe (integration) split.
- **Flyway vs. Liquibase.** Both are fine. Flyway's plain versioned SQL keeps the schema visible.
- **One DB user vs. owner + app roles.** One user is simpler. Separate roles enforce least privilege and are **required** for RLS (table owners bypass RLS unless forced, and superusers always bypass it).
- **JPA from day one vs. when needed.** No entities exist in P1.

## Decision
Java 21, **Spring Boot 4.0.x**, **Maven** (with wrapper), **Flyway** SQL migrations, PostgreSQL 17 with a dedicated `storeforge` schema owned by `storeforge_owner` (migrations) and a runtime role `storeforge_app` (DML only via default privileges, plus DB-side `statement_timeout` and `idle_in_transaction_session_timeout`). JDBC only in P1; Spring Data JPA is added in P2 with the first aggregate. Spring Modulith 2.0 verifies module boundaries.

## Why We Chose It
- Starting on the current Spring generation avoids a 3→4 migration mid-project.
- The owner/app split costs one init script now. Retrofitting it after 30 migrations means re-granting everything and risking RLS gaps.
- DB-side timeouts are a safety net that works even when application code is wrong (e.g. a remote call inside a transaction).
- Virtual threads are enabled: blocking JDBC/HTTP code stays simple. The connection pool (10) remains the real concurrency limit, which is deliberate and observable via Hikari metrics.

## Trade-offs
- Spring Boot 4 has fewer community answers; some library integrations lag.
- Two sets of DB credentials to manage.
- `-Werror` with `-Xlint:all` makes the build strict. Warnings must be fixed or explicitly suppressed.

## Consequences
- Every environment provisions the two roles (Compose init script, Testcontainers uses the same script, production uses infra tooling).
- Migrations must not assume superuser rights. Extensions must be trusted ones (or provisioned by infra).
- Integration tests must run as the app role, never as a superuser.
