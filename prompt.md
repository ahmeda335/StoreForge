# Task: Design a Shopify-Like SaaS Platform as a Deep Learning Project

I want to build a serious learning project inspired by Shopify.

The goal is NOT to clone Shopify's UI or reproduce every Shopify feature. The goal is to build a realistic **multi-tenant commerce SaaS/platform** that allows multiple independent businesses to create and operate their own online stores.

I want this project specifically to teach me **backend engineering, SaaS architecture, infrastructure, distributed systems, reliability, security, observability, testing, and production engineering**.

## VERY IMPORTANT

For this task:

**DO NOT WRITE OR MODIFY ANY CODE.**

Do not implement anything.

Do not create files.

Do not make migrations.

Do not install dependencies.

Do not start the application.

Your only responsibility is to deeply analyze the project idea and produce a **complete implementation and learning plan**.

The final result should be detailed enough that I can use it as the project's engineering blueprint.

---

# 1. First: Define the Project

Give the project a name and clearly define:

- What the platform does
- Who uses it
- What problems it solves
- What is inside the platform
- What is outside the platform
- What makes it a SaaS platform rather than simply an e-commerce application
- What makes it a learning project for infrastructure/platform engineering

Define the main actors, for example:

- Platform administrator
- Merchant/store owner
- Store staff
- Customer
- Third-party application/developer
- Background workers/services

Explain the responsibilities and permissions of each.

---

# 2. Define the Scope

Create a detailed feature map.

Separate features into:

### MVP
The minimum system that is still architecturally meaningful.

### Phase 2
Features that introduce more advanced backend/distributed-system concepts.

### Phase 3
Production/infrastructure/scaling features.

### Advanced / Optional
Features that are useful for deeper learning but should not block the main project.

For every feature, explain:

- What it does
- Why it exists
- What engineering concept it teaches
- Dependencies on other features
- Important edge cases

Do not blindly add features just because Shopify has them.

The goal is to maximize **engineering learning value**.

---

# 3. Architecture

Design the complete architecture.

Start with a **modular monolith unless there is a strong reason not to**.

Explain:

- Why a modular monolith is appropriate initially
- Module boundaries
- Domain boundaries
- Dependencies between modules
- Internal communication
- How the architecture could evolve into distributed services later

Define the main modules, such as:

- Identity & Authentication
- Tenants / Stores
- Merchant Management
- Catalog
- Products
- Product Variants
- Inventory
- Customers
- Cart
- Checkout
- Orders
- Payments
- Discounts
- Shipping
- Notifications
- Webhooks
- Developer/API Platform
- Billing
- Analytics
- Audit
- Platform Administration

Do not assume these exact modules are correct. Analyze and adjust them.

For every module explain:

- Responsibility
- Owned data
- Public API
- Events it publishes
- Events it consumes
- Important invariants
- Security considerations

---

# 4. Multi-Tenancy

This is one of the most important parts of the project.

Design how multiple stores coexist in the same platform.

Analyze possible strategies:

- Shared database / shared schema
- Shared database / separate schema
- Database per tenant
- Hybrid approaches

Choose an initial strategy and explain why.

Then explain how the architecture could evolve toward sharding or tenant isolation at larger scale.

Cover:

- Tenant identification
- Request context
- Authorization
- Database queries
- Repository boundaries
- Background jobs
- Caching
- Object storage
- Search
- Events
- Webhooks
- Logging
- Metrics
- Rate limiting

Most importantly:

Explain how to prevent **cross-tenant data leakage**.

Include testing strategies specifically designed to detect tenant-isolation bugs.

---

# 5. Domain Model

Design the major domain entities.

For each entity provide:

- Purpose
- Important fields
- Relationships
- Ownership
- Lifecycle
- Invariants
- Important indexes
- Unique constraints
- Potential concurrency problems

Cover at least:

- User
- Role
- Permission
- Tenant
- Store
- StoreDomain
- Product
- ProductVariant
- InventoryItem
- InventoryTransaction
- Customer
- Cart
- CartItem
- Order
- OrderItem
- Payment
- Refund
- Discount
- Coupon
- Shipment
- WebhookSubscription
- WebhookDelivery
- APIKey / OAuth application
- AuditLog

Don't blindly create entities. Explain where aggregates/value objects make more sense.

---

# 6. Database Design

Design the PostgreSQL architecture.

Explain:

- Tables
- Relationships
- Primary keys
- Foreign keys
- Unique constraints
- Indexes
- Composite indexes
- Soft deletion vs hard deletion
- Optimistic locking
- Pessimistic locking
- Transactions
- Isolation levels
- Migration strategy

Identify the operations that require strong transactional guarantees.

Especially analyze:

### Inventory

How do we prevent:

```text
Stock = 1

Customer A → buys item
Customer B → buys item
```

from resulting in:

```text
Stock = -1
```

Explain possible approaches and select one.

Also discuss:

- Race conditions
- Lost updates
- Deadlocks
- Idempotency
- Retry behavior

---

# 7. Authentication & Authorization

Design authentication from the beginning.

Cover:

- Registration
- Login
- Password hashing
- Password reset
- Email verification
- Session management
- Access tokens
- Refresh tokens
- Token rotation
- Logout
- Token revocation
- MFA as an advanced feature
- Account lockout/rate limiting

For authorization cover:

- RBAC
- Permissions
- Tenant-level permissions
- Store-level permissions
- Resource ownership
- Platform administrators

Explain exactly where authentication ends and authorization begins.

Discuss common security mistakes.

---

# 8. API Design

Design the API architecture.

Cover:

- REST conventions
- Resource naming
- HTTP semantics
- Status codes
- Pagination
- Filtering
- Sorting
- Searching
- API versioning
- Error responses
- Validation
- Idempotency keys
- Correlation/request IDs
- Rate limiting

Design examples of important endpoints, but do not implement them.

Also design:

### Public Storefront API

### Merchant/Admin API

### Platform Admin API

### Developer API

Explain why these APIs should not necessarily have identical security models.

---

# 9. Events & Asynchronous Processing

Design an event-driven architecture where appropriate.

Examples:

```text
OrderCreated
PaymentAuthorized
PaymentFailed
InventoryReserved
InventoryReleased
OrderCancelled
RefundCreated
WebhookDeliveryRequested
EmailRequested
```

For every important event explain:

- Producer
- Consumer
- Payload
- Delivery semantics
- Idempotency
- Retry strategy
- Failure behavior

Study and include:

- Transactional Outbox Pattern
- Inbox Pattern if appropriate
- At-least-once delivery
- Idempotent consumers
- Dead-letter queues
- Retry with backoff
- Event ordering
- Event versioning

Do NOT introduce Kafka/RabbitMQ just for the sake of using them.

Explain when a database-backed job queue is enough and when a real message broker becomes justified.

---

# 10. Payments

Design a payment abstraction rather than tightly coupling the application to one provider.

For example:

```text
PaymentProvider
    |
    +-- StripeProvider
    +-- FakePaymentProvider
```

Explain:

- Payment lifecycle
- Authorization
- Capture
- Failure
- Refund
- Webhooks
- Duplicate webhooks
- Idempotency
- Payment state machine
- Security
- Never storing sensitive payment information unnecessarily

Use a sandbox provider for learning.

---

# 11. Webhooks

Build webhooks as a serious platform capability.

Design:

- Subscription
- Event selection
- Signing
- Delivery
- Retries
- Exponential backoff
- Timeout
- Dead-letter handling
- Delivery history
- Idempotency
- Replay
- Secret rotation

Explain how a merchant's external system could safely receive:

```text
order.created
payment.completed
inventory.updated
```

---

# 12. Developer Platform

This is important because I want to understand how a platform becomes extensible.

Design:

- API keys
- OAuth applications
- Scopes
- App installation
- Access tokens
- Webhooks
- API versioning
- Rate limiting
- Developer documentation
- API usage metrics

Describe a small example third-party application that integrates with the platform.

---

# 13. Caching

Analyze where caching is useful.

Consider:

- Redis
- Local in-memory cache
- HTTP caching
- Database caching
- Cache-aside
- TTL
- Cache invalidation

For each proposed cache explain:

- What is cached
- Why
- TTL
- Invalidation strategy
- Failure behavior

Do not cache everything.

---

# 14. Search

Design product search.

Start with PostgreSQL if appropriate.

Then explain when a dedicated search engine such as Elasticsearch/OpenSearch becomes justified.

Discuss:

- Full-text search
- Filtering
- Faceting
- Ranking
- Index synchronization
- Eventual consistency

---

# 15. Observability

This is a major learning goal.

Use **OpenTelemetry as the preferred observability standard**.

Design:

### Logs

- Structured logging
- JSON logs
- Log levels
- Correlation IDs
- Trace IDs
- Tenant IDs
- Sensitive-data redaction

### Metrics

Define useful metrics such as:

```text
http_requests_total
http_request_duration
database_query_duration
orders_created_total
payments_failed_total
inventory_reservation_failures_total
webhook_delivery_failures_total
queue_depth
```

Explain which should be counters, gauges, and histograms.

### Distributed Tracing

Use OpenTelemetry to trace:

```text
HTTP request
    ↓
Controller
    ↓
Service
    ↓
Database
    ↓
Event
    ↓
Worker
    ↓
External API
```

Explain:

- Trace
- Span
- Context propagation
- Sampling
- Attributes
- Instrumentation

Recommend an observability stack, for example:

```text
OpenTelemetry
      ↓
Collector
      ↓
Metrics / Logs / Traces backend
      ↓
Grafana
```

Do not assume Grafana is the only possible choice; explain alternatives.

---

# 16. Monitoring & Alerting

Design what should be monitored in production.

Include:

- Availability
- Latency
- Error rate
- Database health
- CPU
- Memory
- Disk
- Connection pools
- Queue depth
- Worker failures
- Payment failures
- Webhook failures

Introduce:

- SLIs
- SLOs
- Error budgets

Give examples of alerts and explain why each alert matters.

---

# 17. Testing Strategy

I want testing to be a first-class part of the project.

Design a testing pyramid covering:

### Unit tests

Domain logic, value objects, business rules.

### Integration tests

Spring Boot + PostgreSQL/Testcontainers.

### API tests

REST API behavior, authentication, authorization, validation.

### Contract tests

Especially for external integrations and webhooks.

### End-to-end tests

Important business flows.

### Concurrency tests

Especially:

- Inventory
- Payments
- Duplicate requests
- Order creation

### Security tests

- Authentication bypass
- Authorization bypass
- Tenant isolation
- IDOR
- Rate limiting

### Load tests

Use a tool such as k6.

Explain exactly what scenarios should be load-tested.

---

# 18. Security

Perform a security threat analysis.

Cover at least:

- OWASP API Security
- SQL injection
- XSS
- CSRF where relevant
- SSRF
- IDOR
- Broken authorization
- Credential attacks
- Rate limiting
- Secret management
- Encryption
- Password storage
- Sensitive data logging
- Webhook signature verification
- Replay attacks
- Dependency vulnerabilities

Explain how security should be incorporated into the architecture rather than added at the end.

---

# 19. Infrastructure

Design how the project runs locally and in production.

Start with:

```text
Docker
Docker Compose
```

Then explain the evolution toward:

```text
Cloud
Load Balancer
Application instances
PostgreSQL
Redis
Message broker
Object storage
Observability
```

Discuss:

- Reverse proxy
- TLS
- DNS
- Environment configuration
- Secrets
- Health checks
- Readiness/liveness
- Graceful shutdown
- Connection pools
- Resource limits

Then explain what Kubernetes would add and **when it is worth learning for this project**.

Do not introduce Kubernetes prematurely.

---

# 20. CI/CD

Design a complete CI/CD pipeline.

It should eventually perform:

```text
Commit
 ↓
Build
 ↓
Unit Tests
 ↓
Static Analysis
 ↓
Dependency/Security Scan
 ↓
Integration Tests
 ↓
Build Docker Image
 ↓
Push Image
 ↓
Deploy
 ↓
Smoke Tests
```

Discuss:

- GitHub Actions
- Docker image versioning
- Database migrations
- Rollbacks
- Environment separation
- Secrets
- Deployment strategies

---

# 21. Reliability

Design for failure.

For each external dependency ask:

> What happens if this is unavailable?

Analyze:

- PostgreSQL unavailable
- Redis unavailable
- Payment provider unavailable
- Email provider unavailable
- Message broker unavailable
- Webhook destination unavailable

Introduce where appropriate:

- Timeout
- Retry
- Exponential backoff
- Circuit breaker
- Bulkhead
- Fallback
- Idempotency

Explain when each pattern should and should not be used.

---

# 22. Performance

Create a performance-learning plan.

Identify likely bottlenecks:

- Database queries
- N+1 queries
- Large result sets
- Connection pools
- Lock contention
- Serialization
- External API calls
- Cache misses

Explain how I should **measure first and optimize second**.

Include profiling and load-testing methodology.

---

# 23. Code Quality & Project Structure

Because I want to become a better engineer, don't only focus on architecture.

Define:

- Package structure
- Naming conventions
- Class responsibilities
- Dependency direction
- DTO conventions
- Domain models
- Exception handling
- Validation
- Configuration
- Logging conventions
- Transaction boundaries
- API conventions

Prefer clean, maintainable code over clever abstractions.

Explain where SOLID, DDD, Clean Architecture, Hexagonal Architecture, etc. are actually useful and where applying them would be overengineering.

---

# 24. Frontend

The frontend should exist primarily to exercise the platform.

Design:

### Merchant Admin

- Store management
- Product management
- Inventory
- Orders
- Customers
- Analytics
- Integrations
- API/Webhook management

### Storefront

- Product listing
- Product details
- Cart
- Checkout
- Order confirmation

Use Angular.

Do not spend excessive effort on visual design.

The backend/platform architecture is the primary learning objective.

---

# 25. Infrastructure Experiments

This is extremely important.

Don't just build the application.

Define experiments where I deliberately break things.

Examples:

1. Kill the database.
2. Kill Redis.
3. Delay the payment provider.
4. Send the same webhook 10 times.
5. Send the same payment callback twice.
6. Create 100 concurrent purchases for one remaining product.
7. Make one tenant generate huge traffic.
8. Make a worker crash halfway through processing.
9. Introduce a slow database query.
10. Stop the message broker.

For each experiment explain:

- What should happen
- What should NOT happen
- What should appear in logs
- What metrics should change
- What traces should show
- How the system recovers

This should become part of the learning experience.

---

# 26. Technology Selection

Create a technology decision matrix.

For every technology explain:

- What problem it solves
- Why it is being selected
- Alternatives
- When it should be introduced
- What I should study before using it

Consider technologies such as:

```text
Java
Spring Boot
PostgreSQL
Redis
Docker
Docker Compose
RabbitMQ
Kafka
OpenTelemetry
Prometheus
Grafana
Loki
Tempo
Elasticsearch/OpenSearch
Testcontainers
k6
GitHub Actions
Kubernetes
Nginx/Traefik
Object Storage
```

Do NOT force all of them into the final architecture.

Some should intentionally remain optional or future-stage technologies.

---

# 27. What I Should Study Before Coding

This section is extremely important.

Create a **prerequisite learning map**.

For every concept/technology that appears in the project, tell me:

```text
Concept
↓
Why I need it
↓
What I should understand first
↓
How deeply I need to understand it
↓
Where it will be used in the project
```

For example:

```text
Transactional Outbox
    ↓
Understand database transactions
    ↓
Understand ACID
    ↓
Understand failure between DB + message broker
    ↓
Then study Outbox Pattern
    ↓
Then implement it
```

Do the same for:

- Multi-tenancy
- OAuth2
- JWT
- PostgreSQL locking
- Transactions
- Redis
- Message queues
- Kafka
- Eventual consistency
- Idempotency
- Distributed tracing
- OpenTelemetry
- Docker
- Kubernetes
- Load balancing
- Rate limiting
- Circuit breakers
- Observability
- SLOs
- etc.

Clearly mark each topic:

**Must understand before implementation**

**Can learn while implementing**

**Advanced / study later**

---

# 28. Tips, Tricks & Engineering Lessons

Create a dedicated section of practical engineering advice.

Include things such as:

- Don't introduce distributed systems before understanding the domain.
- Don't use microservices because they look impressive.
- Measure before optimizing.
- Prefer explicit boundaries.
- Treat external APIs as unreliable.
- Assume requests can be duplicated.
- Assume messages can be duplicated.
- Assume workers can crash.
- Never trust the client.
- Tenant isolation must be enforced server-side.
- Database constraints are part of business correctness.
- Observability should be designed alongside features.
- Every asynchronous operation needs a failure strategy.
- Every external integration needs timeout/retry/idempotency considerations.

Add many more lessons that are relevant to this project.

---

# 29. Common Mistakes

Give me a list of mistakes I am likely to make while building this project.

For example:

- Premature microservices
- Overusing Kafka
- Overusing Redis
- Incorrect transaction boundaries
- Missing database indexes
- N+1 queries
- Poor tenant isolation
- JWT misuse
- Missing authorization checks
- Non-idempotent payment handling
- Ignoring webhook duplication
- Logging sensitive information
- No correlation IDs
- No observability
- Testing only the happy path
- Overengineering abstractions

Explain how to recognize each mistake.

---

# 30. Final Implementation Roadmap

Finally produce the complete roadmap in dependency order.

Something similar to:

```text
Phase 0
Architecture + prerequisites

Phase 1
Project foundation

Phase 2
Identity + authentication

Phase 3
Multi-tenancy

Phase 4
Catalog

Phase 5
Inventory + concurrency

Phase 6
Cart + checkout

Phase 7
Orders

Phase 8
Payments

Phase 9
Events + asynchronous processing

Phase 10
Webhooks

Phase 11
Developer platform

Phase 12
Observability

Phase 13
Security hardening

Phase 14
Load testing

Phase 15
Production infrastructure

Phase 16
Failure experiments

Phase 17
Scaling experiments
```

But determine the actual phases yourself based on dependencies.

For every phase include:

- Objective
- Features
- Architecture concepts
- Technologies
- Prerequisites
- What I should study first
- Implementation tasks
- Testing tasks
- Observability tasks
- Security tasks
- Failure scenarios
- Definition of Done
- What I should NOT implement yet

---

# 31. Final Deliverables

Your response must finish with:

### A. Complete Architecture Overview

One coherent description of the final system.

### B. Technology Stack

With justification for every major technology.

### C. Learning Roadmap

Ordered by prerequisites.

### D. Implementation Roadmap

Ordered by project dependencies.

### E. Architecture Evolution

Show:

```text
Modular Monolith
      ↓
Async Processing
      ↓
External Integrations
      ↓
Production Infrastructure
      ↓
Scale Experiments
      ↓
Selective Service Extraction
      ↓
Sharding / Advanced Scaling
```

Explain what triggers each transition.

### F. Study Checklist

A checklist of concepts I should understand before reaching each stage.

### G. Engineering Principles

The important lessons this project should teach me.

### H. Risk Register

The biggest architectural and implementation risks and how to mitigate them.

---

# Important Final Rule

Do NOT start implementing.

Do NOT generate code.

Do NOT create files.

Do NOT give me a superficial tutorial.

I want a **serious engineering blueprint** for a long-term learning project.

Whenever you recommend a technology or architectural pattern, explain the **problem that caused it to exist**.

The objective is that when I encounter something I don't understand, I can stop at that point, study the underlying concept, and then continue building.

I want to finish this project understanding not only **how to build an application**, but how to design, operate, observe, secure, test, scale, and evolve a **real SaaS platform**.


# Task: Implement the Shopify-Like SaaS Platform From the Approved Plan

We already have a detailed architecture, technology-selection, learning roadmap, and implementation plan for this project.

Now I want you to **start implementing the project according to that plan**.

The goal remains the same:

Build a serious **multi-tenant commerce SaaS/platform engineering project inspired by Shopify**, while using the project to learn backend engineering, infrastructure, distributed systems, reliability, security, observability, testing, and production engineering.

---

# IMPORTANT: Follow the Existing Plan

Treat the previously created architecture and roadmap as the source of truth.

Before implementing anything:

1. Read and understand the entire plan.
2. Identify the current phase.
3. Identify its prerequisites.
4. Identify what should be implemented now.
5. Identify what should explicitly NOT be implemented yet.
6. If the existing plan contains a contradiction or an important architectural problem, stop and explain it before making changes.

Do not silently redesign the architecture.

If you believe something should change, explain:

- The original decision
- The problem with it
- The proposed change
- Why the new approach is better
- What parts of the existing plan are affected

Then wait for approval before making a major architectural change.

---

# Implementation Rules

Implement the project **incrementally and in dependency order**.

Do not try to build the entire platform at once.

For each phase:

1. Understand the requirements.
2. Design the implementation.
3. Implement it.
4. Write tests.
5. Add observability.
6. Add appropriate security.
7. Verify it.
8. Document what was built.
9. Only then move to the next phase.

The project must remain runnable after every meaningful phase.

---

# Code Quality

Treat this as a real production-oriented project.

I want:

- Clean, readable code
- Meaningful names
- Clear package/module boundaries
- Small focused classes
- Explicit dependencies
- Proper transaction boundaries
- Proper exception handling
- Validation
- Database constraints
- Good API design
- No unnecessary abstractions
- No premature optimization
- No unnecessary design patterns
- No unnecessary microservices

Prefer simple solutions when they are sufficient.

Do not add complexity simply because a technology or pattern appears in the roadmap.

---

# Architecture

Start with the architecture defined in the plan.

If the plan specifies a modular monolith, maintain clear module boundaries.

Do not convert modules into microservices unless the roadmap explicitly reaches that stage and there is a concrete engineering reason.

Maintain dependency direction between modules.

Avoid accidental coupling.

Document important architectural boundaries.

---

# Documentation Is Part of the Implementation

This is extremely important.

I don't want documentation written only at the end.

**Every major implementation phase must produce documentation.**

Create a dedicated documentation structure for the project.

For example:

```text
docs/
├── architecture/
├── decisions/
├── domains/
├── database/
├── security/
├── api/
├── events/
├── infrastructure/
├── observability/
├── testing/
├── operations/
├── learning/
└── troubleshooting/
```

Adjust this structure if the project needs something better.

---

# Architecture Documentation

Document the system architecture.

Include:

- System overview
- Component relationships
- Module boundaries
- Request flows
- Data flows
- Authentication flow
- Authorization flow
- Checkout flow
- Payment flow
- Inventory flow
- Event flow
- Webhook flow
- Background job flow

Use Mermaid diagrams where appropriate.

For example:

```text
Client
  ↓
API
  ↓
Application Layer
  ↓
Domain
  ↓
Infrastructure
  ↓
PostgreSQL
```

And more detailed diagrams for complex flows.

The diagrams should remain synchronized with the implementation.

---

# Architecture Decision Records

Whenever an important architectural decision is made, create an ADR.

For example:

```text
docs/decisions/
├── ADR-001-modular-monolith.md
├── ADR-002-multi-tenancy-strategy.md
├── ADR-003-authentication-strategy.md
├── ADR-004-inventory-concurrency.md
└── ...
```

Each ADR should contain:

```text
Context
Problem
Options Considered
Decision
Why We Chose It
Trade-offs
Consequences
```

Do not create ADRs for trivial decisions.

Create them for decisions that would be useful to understand six months later.

---

# Learning Documentation

This is a learning project, so document the **engineering reasoning**, not only how to run the code.

For every important technology or pattern, document:

### Problem

What problem does it solve?

### Why

Why do we need it in this project?

### Alternatives

What alternatives did we consider?

### Decision

What did we choose?

### Mental Model

Explain how it works conceptually.

### Project Usage

Where is it used in our system?

### Failure Cases

What can go wrong?

### Testing

How do we verify it?

### Observability

How do we know it is working correctly?

For example:

```text
Transactional Outbox

Problem:
Database update and event publishing can fail independently.

Why we need it:
Order creation must not succeed while the corresponding event is lost.

Mental Model:
...

Implementation:
...

Failure scenarios:
...

Testing:
...

Observability:
...
```

---

# Technology Documentation

Whenever introducing a technology such as:

- PostgreSQL
- Redis
- Docker
- RabbitMQ/Kafka
- OpenTelemetry
- Prometheus
- Grafana
- Testcontainers
- k6
- Kubernetes

create or update documentation explaining:

1. What it is
2. What problem it solves
3. Why we need it
4. Why we chose it
5. Alternatives
6. How it works in our architecture
7. How it is configured
8. How it is tested
9. What happens when it fails
10. When we should NOT use it

The documentation should teach me the technology through our actual project.

---

# Observability

OpenTelemetry is preferred.

Do not treat observability as a final feature.

Introduce it progressively as the architecture requires it.

Implement:

- Structured logging
- Correlation IDs
- Trace IDs
- Metrics
- Distributed traces
- OpenTelemetry instrumentation
- Health endpoints
- Readiness/liveness where appropriate
- Useful dashboards
- Useful alerts

Document:

```text
What we observe
Why we observe it
Where the signal comes from
How to investigate a failure
```

When implementing an important flow, make sure I can actually observe it.

For example:

```text
POST /orders
    ↓
HTTP span
    ↓
Order service span
    ↓
Database span
    ↓
Outbox event
    ↓
Worker span
    ↓
Payment API span
```

---

# Testing

Testing must be implemented alongside the feature.

For every important feature determine the appropriate testing level:

- Unit
- Integration
- API
- Contract
- End-to-end
- Concurrency
- Security
- Load

Do not blindly create tests just to increase coverage.

Tests should verify important behavior and invariants.

Pay special attention to:

- Tenant isolation
- Authorization
- Inventory concurrency
- Duplicate requests
- Idempotency
- Payment callbacks
- Webhook delivery
- Event processing
- Transaction boundaries
- Failure recovery

Use Testcontainers where appropriate.

Use k6 for realistic load testing when we reach the relevant stage.

---

# Security

Security must be implemented as part of the architecture.

For every feature consider:

- Authentication
- Authorization
- Tenant isolation
- Input validation
- Rate limiting
- Sensitive data
- Secrets
- Audit logging
- Abuse scenarios

Never postpone all security until the end.

---

# Database

Treat database design as part of the domain design.

Use:

- Proper constraints
- Proper indexes
- Foreign keys
- Transactions
- Appropriate isolation
- Optimistic/pessimistic locking where justified
- Database migrations

Do not solve every consistency problem in application code if the database can enforce the invariant.

Document important database decisions.

---

# API

Implement APIs according to the previously designed API strategy.

Maintain:

- Consistent naming
- Proper HTTP semantics
- Validation
- Error format
- Pagination
- Filtering
- Sorting
- Authentication
- Authorization
- Idempotency
- API versioning where required

Generate/update API documentation where appropriate.

---

# Events & Async Processing

When the roadmap reaches asynchronous processing:

Implement the patterns defined in the architecture.

Pay particular attention to:

- Transactional Outbox
- Idempotent consumers
- Retries
- Backoff
- Dead-letter handling
- Event versioning
- Duplicate messages
- Worker crashes

Do not assume messages are delivered exactly once.

Document the delivery semantics explicitly.

---

# Infrastructure

Keep local development simple and reproducible.

Use Docker/Docker Compose where appropriate.

The development environment should allow another developer to clone the project and understand how to run it.

Document:

- Prerequisites
- Environment variables
- Local services
- Database setup
- Migrations
- Observability stack
- Running tests
- Running load tests

Do not introduce Kubernetes until the roadmap reaches the point where it teaches a meaningful concept.

---

# Failure Experiments

When the project reaches the relevant maturity, deliberately test failures.

Examples:

- Database unavailable
- Redis unavailable
- Payment provider timeout
- Duplicate payment webhook
- Duplicate order request
- Worker crash
- Message broker unavailable
- Slow database query
- High concurrent inventory demand
- One tenant generating abnormal traffic

For each experiment document:

```text
Experiment
Expected behavior
Actual behavior
What failed
Why it failed
How it was detected
How the system recovered
What we changed
```

These experiments are part of the project, not optional notes.

---

# Git & Commits

Keep the implementation logically organized.

Prefer small, meaningful commits.

Commit messages should communicate the engineering change.

Avoid giant commits containing an entire phase.

If you are operating in an existing Git repository, inspect the current state before making changes.

Do not destroy or overwrite existing work without understanding it.

---

# Implementation Cycle

For every phase use this workflow:

```text
Understand
    ↓
Plan
    ↓
Implement
    ↓
Test
    ↓
Observe
    ↓
Verify
    ↓
Document
    ↓
Review
    ↓
Next Phase
```

Before starting a phase, explain briefly:

- What we are building
- Why we are building it now
- What concepts it teaches
- What prerequisites it depends on

Then implement it.

After implementation, report:

- What changed
- Files/modules affected
- Important design decisions
- Tests added
- How it was verified
- Observability added
- Security considerations
- Documentation created
- Known limitations
- What should be studied before the next phase

---

# Do Not Hide Complexity

If something is difficult, do not silently abstract it away.

If we implement something like:

- Multi-tenancy
- Concurrency control
- Transactions
- Idempotency
- Event processing
- Authentication
- Distributed tracing
- Retry systems
- Rate limiting

make the implementation understandable.

I want to be able to inspect the code and understand **why it exists**.

---

# Avoid Cargo-Cult Engineering

This is a learning project.

Do not use a technology simply because:

> "Shopify probably uses it."

Instead ask:

> "What problem does this technology solve, and can we demonstrate that problem in our project?"

For every major technology, I should be able to answer:

```text
What problem does it solve?
Why do we need it?
What happens without it?
What are the alternatives?
What are its trade-offs?
How does it fail?
How do we observe it?
How do we test it?
```

---

# Definition of Done

A feature is NOT done simply because the code compiles.

A meaningful feature should generally have:

- Implementation
- Validation
- Database constraints where appropriate
- Tests
- Security considerations
- Logging
- Metrics/tracing where appropriate
- Documentation
- Error handling
- Failure behavior
- Verification

Not every tiny change requires every item, but every significant feature should be evaluated against them.

---

# Most Important Rule

**Do not rush to the next phase.**

The purpose of this project is learning.

If implementation exposes a concept I should understand first, clearly identify it and document it.

If something is too advanced for the current phase, don't unnecessarily introduce it.

If a technology is not justified yet, postpone it.

If an architectural decision is questionable, tell me.

The final result should not merely be a working Shopify-like application.

It should be a **well-engineered, observable, tested, secure, documented SaaS platform that demonstrates how real production systems are designed and evolved.**