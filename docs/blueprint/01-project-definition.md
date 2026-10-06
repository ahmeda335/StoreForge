# 01 — Project Definition

## Name

**StoreForge**: a multi-tenant commerce platform where independent merchants run their own online stores on shared infrastructure.

## What the platform does

StoreForge lets a merchant sign up, create a store, add a product catalog with variants and stock levels, sell to customers through a storefront, take payments through a provider, and fulfil orders. It also lets the merchant connect outside systems through webhooks and a developer API. One deployment of StoreForge serves **many stores at once**. That is the defining property.

## Who uses it

| Actor | Description | Authenticates via | Can do | Must never be able to do |
|---|---|---|---|---|
| **Platform administrator** | StoreForge operator (you). | Separate admin login, MFA (later), separate token audience | Suspend/reactivate stores, view platform health, inspect webhook/job failures, support impersonation (audited, read-only first) | Read card data (we never have it); act silently. Every admin action is audited |
| **Store owner** | Created the store. | Merchant login (email + password) | Everything inside *their* store, including inviting staff, roles, billing, deleting the store | Touch any other store |
| **Store staff** | Invited by an owner. | Merchant login | What their role's permissions allow (e.g. `products:write`, `orders:read`) | Escalate their own permissions; manage the owner |
| **Customer** | Buys from one specific store. | Guest checkout, or customer account **scoped to one store** | Browse, manage cart, checkout, see own orders | See other customers' data; their account does not exist in other stores |
| **Third-party app / developer** | External software integrating with a store. | API key (private app) or OAuth access token (installed app) | Only the scopes the merchant granted, only on the installing store | Exceed granted scopes; access stores that did not install it |
| **Background workers** | Internal processes (outbox relay, webhook dispatcher, reservation expirer, email sender). | No user identity; they carry an explicit `storeId` in every job payload | Process jobs for the store named in the job | Run "tenant-less" queries over tenant data, except explicit platform-wide maintenance jobs that are named and reviewed as such |
| **Payment provider (external)** | Stripe / fake provider calling back. | Webhook signature | Notify payment state changes | Change state without a valid signature, or twice (idempotency) |

## Problems it solves

For merchants: running an online store without running servers, payment integration, or stock bookkeeping.
For the platform: serving thousands of small stores **cheaply** (shared infrastructure), **safely** (no store sees another's data), and **reliably** (one noisy store does not take down the rest).

## Inside vs. outside the platform

**Inside:** identity, store/tenant management, staff and roles, catalog, inventory, customers, cart and checkout, orders, payment orchestration, discounts, simple shipping rates, notifications (emails as jobs), outbound webhooks, developer API (keys, OAuth apps, scopes), audit, platform administration, basic analytics, observability.

**Outside (integrated, never reimplemented):**
- Card processing and PCI scope: the payment provider (Stripe test mode, plus a fake provider).
- Email delivery: an SMTP provider (Mailpit locally).
- Carrier rate APIs and label printing: out of scope. Shipping uses flat or weight-based rates the merchant configures.
- Tax calculation engines: out of scope. A simple per-store tax rate at most.
- Theme/page builder and a visual storefront editor: explicitly out of scope (UI work with low backend learning value).
- Domain registration: out. Custom domains are *mapped* (StoreDomain), not sold.

## Why it is a SaaS platform, not just an e-commerce app

An e-commerce application serves **one** business. StoreForge is SaaS because:

1. **Multi-tenancy.** One codebase, one deployment, one database cluster serve many isolated stores. Every query, cache key, job, log line, and metric must know *which tenant* it belongs to.
2. **Self-service onboarding.** A store is created by an API call, not by a deployment.
3. **Platform concerns.** Per-tenant rate limits, noisy-neighbour protection, tenant suspension, and platform billing, plus operational tooling across tenants.
4. **Extensibility.** Third parties build on the platform (webhooks, OAuth apps, scopes, API versioning). That is the move from an *application* to a *platform*.
5. **Operated as a service.** SLOs, on-call style alerts, and zero-downtime deploys, because merchants' revenue depends on uptime.

## Why it is a platform-engineering learning project

The domain (stores, products, orders) is intentionally familiar. You spend your mental effort on the engineering underneath it, not on learning the business:

- **Concurrency correctness:** last-item purchases, double submits, duplicate webhooks.
- **Consistency across boundaries:** database plus events plus external payment provider. Outbox, idempotency, at-least-once delivery.
- **Isolation:** tenant data leakage is the canonical SaaS security bug.
- **Operating it:** observability, failure experiments, load testing, deployment and rollback.
- **Evolution:** a modular monolith that *could* be split, with the triggers for splitting written down.

The project is a success when you can explain *why* each mechanism exists. That means being able to break it on purpose and watch the failure in traces and metrics.
