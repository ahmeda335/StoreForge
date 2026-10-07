# Lombok

**Problem.** Java needs a lot of ceremony for simple things. A service with 9 dependencies needs a 12-line constructor that only copies parameters into fields, and an entity with 26 columns needs 26 accessor methods. That noise hides the lines that matter.

**Why we need it.** Readability of services and entities. The owner asked for less boilerplate ([ADR-015](../decisions/ADR-015-lombok.md)).

**Alternatives.** Records (already used for DTOs, value objects and views, but not usable for JPA entities or Spring beans with behaviour); IDE-generated code (still in the source, still to read and maintain); Kotlin (a different language).

**Decision.** Four Lombok features: `@RequiredArgsConstructor`, `@Slf4j`, `@Getter`, `@NoArgsConstructor(access = PROTECTED)`. Nothing that generates setters, `equals`/`hashCode` or `toString`.

**Mental model.**
- Lombok is an **annotation processor** that modifies the class during compilation. The `.class` file contains the constructor or getter; the `.java` file doesn't. At runtime there is no Lombok (the dependency has `provided` scope).
- `@RequiredArgsConstructor` creates a constructor for every **`final` field without an initializer**, in declaration order. Spring injects through it like any single constructor. A field initialized inline (`private final Cache c = ...`) is not a parameter.
- With `lombok.accessors.fluent = true`, `@Getter private Status status;` generates `status()`, matching record accessors, so callers can't tell an entity from a record.
- **Why not `@Data` on entities:** it adds setters (bypassing state machines like `Order.markPaid()`), `equals`/`hashCode` on mutable fields (JPA entities change identity when their id is assigned), and a `toString()` that can trigger lazy loading and print secrets (`passwordHash`).

**Project usage.** About 75 classes: application services, controllers, SQL stores (`@RequiredArgsConstructor`), entities `User`, `Store`, `Role`, `Product`, `Variant`, `Order` (`@Getter`, `@NoArgsConstructor`), loggers (`@Slf4j`).

**Configuration.** `pom.xml`: `org.projectlombok:lombok` (`provided`, version from the Spring Boot BOM) plus an explicit `annotationProcessorPaths` entry (required from JDK 23). `lombok.config` at the project root.

**Failure cases.**
- A hand-written method with the same name as a generated getter makes Lombok warn, and with `-Werror` the build fails. Add `@Getter(AccessLevel.NONE)` to that field.
- Adding a `final` field to a `@RequiredArgsConstructor` class changes the constructor silently (fine for Spring; tests that call the constructor directly must be updated).
- The IDE shows "cannot resolve method" without the Lombok plugin or annotation processing enabled.

**Testing.** No special tests: the whole suite (163 tests) passed unchanged after the migration, which is the evidence that behaviour didn't change.

**Observability.** None.

**When NOT to use it.** Value objects and DTOs (use records); anything with logic in the constructor or accessor; `@Data`/`@Setter`/`@ToString`/`@EqualsAndHashCode` on domain classes; `@SneakyThrows` (hides checked exceptions).
