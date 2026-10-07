# ADR-015: Lombok for constructors, getters and loggers, with strict limits

- **Status:** Accepted
- **Date:** 2026-10-07
- **Phase:** between P8 and P9 (requested by the project owner)

## Context
After eight phases the code had a lot of mechanical Java: constructors that only assign injected dependencies (some with 9+ parameters), one-line accessor methods on entities (`Order` had 26), and logger declarations. The owner asked to use Lombok wherever it reduces lines, for existing and future code.

## Problem
Which Lombok features do we use, and which do we forbid, so the code gets shorter without hiding behaviour this learning project wants visible?

## Options Considered
1. No Lombok (records already cover DTOs/value objects): explicit, but verbose services and entities.
2. Lombok everywhere, including `@Data`, `@Builder`, `@ToString`, `@EqualsAndHashCode` on entities: shortest code, but known JPA pitfalls (equals/hashCode on mutable entities, lazy-loading `toString`, accidental setters that bypass the state machine) and secrets in generated `toString`.
3. **A small allow-list of Lombok features.**

## Decision
Allowed:
| Feature | Where | Replaces |
|---|---|---|
| `@RequiredArgsConstructor` | Spring beans whose constructor only assigns `final` dependencies; enums with constant fields | Hand-written injection constructors |
| `@Slf4j` | Any class that logs | `private static final Logger log = LoggerFactory.getLogger(...)` |
| `@Getter` (field- or class-level) | Entities, exceptions, small infrastructure classes | Trivial `T name() { return name; }` accessors |
| `@NoArgsConstructor(access = PROTECTED)` | JPA entities | `protected Entity() { // for JPA }` |

Configuration (`lombok.config`): `lombok.accessors.fluent = true` keeps the record-style accessor names already used everywhere (`order.status()`, not `getStatus()`); `addLombokGeneratedAnnotation` marks generated code for coverage tools; `config.stopBubbling`.

**Not used:** `@Data`, `@Setter`, `@ToString`, `@EqualsAndHashCode`, `@Builder`, `@Value`, `@SneakyThrows`, `val`/`var`. Records remain the tool for DTOs, value objects and views.

Stays hand-written:
- constructors with logic (e.g. `LoginService` computing a dummy hash, `RateLimiter`);
- constructors with annotated parameters (`@Value`);
- accessors that transform (`Store.id()` → `StoreId`, `Product.optionNames()` → list copy, `version()` null → 0);
- redacting `toString()` overrides.

## Why We Chose It
- About 380 fewer lines with no behaviour change (163 tests unchanged and green).
- No setters on entities: state still changes only through domain methods (state machines, invariants).
- No generated `toString`, so no accidental logging of password hashes, tokens or lazy collections.

## Trade-offs
- An annotation processor in the build. IDEs need the Lombok plugin (bundled in IntelliJ).
- Generated code is invisible in the source. That is acceptable for these four trivial patterns only.
- With `-Werror`, Lombok warnings break the build (for example "method already exists" when a hand-written accessor shares a field's name). Use `@Getter(AccessLevel.NONE)` on such fields.

## Consequences
- New code follows the allow-list above. Reviews reject `@Data`/`@Setter`/`@ToString` on domain classes.
- `docs/blueprint/23-code-quality.md` and [learning/lombok.md](../learning/lombok.md) describe the rules.
