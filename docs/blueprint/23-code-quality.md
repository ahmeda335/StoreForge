# 23 — Code Quality and Project Structure

## Package structure (package-by-module, then by layer inside)

```text
dev.storeforge
├── StoreForgeApplication
├── shared/                         ← kernel; Spring Modulith "shared" module
│   ├── web/                        ProblemDetails handler, RequestIdFilter
│   ├── money/ ids/ tenancy/        value objects used everywhere
├── identity/
│   ├── (public API types: IdentityApi, CurrentUser, events)
│   └── internal/
│       ├── web/          controllers + request/response DTOs
│       ├── application/  use-case services (@Transactional)
│       ├── domain/       entities, value objects, state machines
│       └── persistence/  repositories, JDBC queries
├── stores/ ...
├── catalog/ ...
└── jobs/ ...
```
Only types directly in `dev.storeforge.<module>` are visible to other modules. Everything in `internal` is private (verified by Spring Modulith). Small modules can stay flat (no sub-packages) until they grow. Structure follows size, not ceremony.

## Naming

- Use cases are verbs: `PlaceOrder`, or methods on a service named after the business action (`OrderService.cancel`), never `OrderManager`/`OrderHelper`/`OrderUtil`.
- DTOs: `CreateProductRequest`, `ProductResponse`, `ProductSummaryResponse` (as Java `record`s).
- Events: past tense facts `OrderPlaced`, `PaymentCaptured`.
- Exceptions: domain meaning, e.g. `InsufficientStockException`, `InvalidStateTransitionException`.
- Tests: `ClassNameTest` (unit), `ClassNameIT` (integration, run by Failsafe), test methods describe behaviour: `rejectsRefundExceedingCapturedAmount()`.

## Responsibilities and dependency direction

- **Controller:** HTTP ↔ DTO mapping, Bean Validation, calling **one** service method. No business logic, no repositories.
- **Application service:** transaction boundary (`@Transactional` here and only here), authorization check, orchestration, publishing to the outbox.
- **Domain:** invariants and state transitions (methods like `order.cancel(reason, clock)`), with no Spring dependencies except JPA annotations.
- **Repository/persistence:** queries. Tenant-scoped signatures.
- Direction: `web → application → domain ← persistence`. Domain depends on nothing else.

## Lombok ([ADR-015](../decisions/ADR-015-lombok.md))

Allowed: `@RequiredArgsConstructor` (injection-only constructors, enums), `@Slf4j`, `@Getter` (fluent accessors, `order.status()`), `@NoArgsConstructor(access = PROTECTED)` for JPA entities. Forbidden on domain classes: `@Data`, `@Setter`, `@ToString`, `@EqualsAndHashCode`, `@Builder`. Records stay the choice for DTOs, value objects and views. Hand-write constructors with logic, transforming accessors and redacting `toString()`.

## DTO conventions

Request and response DTOs are separate records per use case. **Never expose or bind JPA entities** (mass assignment, lazy-loading exceptions, accidental field leaks). Mapping is written by hand (small static `from(entity)` methods) until it becomes painful. MapStruct is optional later.

## Exceptions

- Domain exceptions extend `StoreForgeException(code, httpStatus hint)`. A single `@RestControllerAdvice` maps them to Problem Details.
- Infrastructure exceptions: `DataIntegrityViolationException` with a known constraint name → 409 with a field error; unknown → 500 (logged at ERROR with requestId).
- No catch-and-ignore. No `catch (Exception e) { log; return null; }`.

## Validation

Syntactic validation at the edge (Bean Validation on DTOs), semantic validation in the domain (constructors of value objects, entity methods), invariants in the database (constraints). The three layers are intentional, not duplication: each catches what the others cannot.

## Configuration

Type-safe `@ConfigurationProperties` records per module (`storeforge.jobs.poll-interval`), validated (`@Validated`) at startup. Fail fast on a bad config. No `@Value` sprinkled through code.

## Logging conventions

SLF4J with parameterized messages; structured key-values via the fluent API (`log.atInfo().addKeyValue("orderId", id).log("Order placed")`); log at state changes; IDs not objects; one log per event (not log-and-rethrow at every layer).

## Transaction boundaries

- `@Transactional` on application service methods only. Read-only methods use `@Transactional(readOnly = true)`.
- `spring.jpa.open-in-view=false` (otherwise DB connections are held during view rendering and lazy loads happen silently in controllers).
- No remote calls (HTTP, email, provider) inside a transaction. Use the outbox, or call after commit.
- Keep transactions short: lock late, commit early.

## Where design principles help vs. hurt

| Principle | Useful here | Overengineering here |
|---|---|---|
| SOLID: SRP | Small services per use-case area | One-method interfaces for every class |
| SOLID: DIP | Ports for payment/email/storage providers (real alternative implementations exist: fake vs. Stripe) | `ProductRepositoryPort` + `ProductRepositoryAdapter` when there's only ever JPA |
| DDD tactical | Aggregates, value objects (Money), state machines, domain events | Repositories-per-entity, factories/specifications for simple CRUD (e.g. store settings) |
| DDD strategic | Module = bounded context; ubiquitous language (`Checkout` ≠ `Order`) | Separate microservice per bounded context |
| Hexagonal/Clean | At **external** boundaries | Full ports/adapters/use-case-interactor ceremony for every CRUD endpoint; separate domain and persistence models everywhere |
| CQRS | Read models for analytics; separate list queries via DTO projections | Separate write/read databases + event sourcing for a catalog |

Rule of thumb: introduce an abstraction when you have **two real implementations or a real test seam**. Not before.
