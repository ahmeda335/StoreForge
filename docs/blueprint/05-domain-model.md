# 05 — Domain Model

## Aggregates first, entities second

An **aggregate** is a cluster of objects changed together under one invariant and saved in one transaction. Other aggregates are referenced **by ID**, not by object reference. This keeps transactions small and makes later extraction possible.

| Aggregate (root) | Contains | Why grouped |
|---|---|---|
| `User` | credentials, email verification state | Password/email invariants |
| `Store` | domains, settings | Handle and primary-domain invariants |
| `StoreMembership` | role assignment | "≥1 owner" spans memberships, enforced in a service with a lock on the store row |
| `Role` | permissions (set of value objects) | Permission set changes atomically |
| `Product` | options, variants (price, SKU, option values) | "≥1 variant", option-combination uniqueness |
| `InventoryItem` | levels (on_hand, reserved) | Stock invariant `available ≥ 0` |
| `InventoryTransaction` | — (append-only ledger row) | Immutable fact |
| `Customer` | addresses | Customer-owned data |
| `Cart` | cart items | Quantity rules |
| `Checkout` | snapshot of totals, reservation reference | One order per checkout |
| `Order` | order items, addresses (snapshots), fulfilment status | Totals immutable after placement |
| `Payment` | payment transactions (auth/capture/void) | Amount invariants |
| `Refund` | — | Separate lifecycle, references Payment |
| `Discount` | coupon codes | Usage limits |
| `Shipment` | shipment items | Quantity ≤ ordered |
| `WebhookSubscription` | — | Secret, topics |
| `WebhookDelivery` | attempts | Per-delivery state machine |
| `ApiKey`, `OAuthApp`, `AppInstallation` | scopes | Credential lifecycle |
| `AuditLog` entry | — | Append-only |

**Value objects** (immutable, no identity, validated on construction): `Money(amountMinor: long, currency)`, `Email` (normalized lowercase), `StoreHandle`, `Sku`, `Quantity` (≥0), `Address`, `Permission` (`resource:action`), `Scope`, `IdempotencyKey`, `Weight`. `Price` is **not** an entity. `OrderItem` holds a *snapshot* (title, sku, unit price) as values, so later catalog edits do not rewrite history.

Notes on prompt entities:
- **Tenant** and **Store** are merged (ADR-002).
- **Permission** is a value object (a fixed enum-like catalog in code), not a table users create. `role_permissions` stores the codes.
- **Coupon** is a code belonging to a `Discount` (one discount → many codes).

## Entity catalog

Common fields on all tenant entities: `id uuid (v7)`, `store_id uuid`, `created_at`, `updated_at`, `version bigint` (optimistic lock) where concurrent edits are plausible.

### User
- **Purpose:** a human merchant or staff account (platform-wide; one user can belong to many stores). Customers are *not* Users (separate, store-scoped).
- **Fields:** `email (citext)`, `password_hash`, `email_verified_at`, `status (ACTIVE/LOCKED/DISABLED)`, `failed_login_count`, `locked_until`, `platform_role (NONE/ADMIN)`.
- **Lifecycle:** registered → verified → (locked ↔ active) → disabled.
- **Invariants/constraints:** `UNIQUE(email)`. Email normalized lowercase.
- **Concurrency:** concurrent failed logins incrementing a counter → atomic `UPDATE ... SET failed_login_count = failed_login_count + 1`.

### Role / Permission
- Role: `store_id`, `name`, `is_system (Owner/Staff templates)`, `permissions text[]` or `role_permissions(role_id, permission)`.
- **Invariants:** `UNIQUE(store_id, name)`. The system Owner role cannot be edited. A member cannot create a role containing permissions they do not hold (anti-escalation).

### StoreMembership (store_members)
- `store_id`, `user_id`, `role_id`, `status (INVITED/ACTIVE/REMOVED)`.
- `UNIQUE(store_id, user_id)`. Invariant: ≥1 active owner. Enforce with `SELECT ... FROM stores WHERE id=? FOR UPDATE` before demote/remove (serializes owner changes per store).

### Store (= Tenant)
- `handle (citext)`, `name`, `status (ACTIVE/SUSPENDED/CLOSED)`, `currency`, `timezone`, `owner_user_id`, `plan`.
- `UNIQUE(handle)`, CHECK on handle format, reserved list in application.
- Lifecycle: ACTIVE ↔ SUSPENDED (admin) → CLOSED (soft, data retained N days).

### StoreDomain
- `store_id`, `hostname (citext)`, `is_primary`, `verified_at`, `verification_token`.
- `UNIQUE(hostname)` globally (one hostname → one store). Partial unique index `UNIQUE(store_id) WHERE is_primary`.
- Edge: hostname takeover. Domains require DNS TXT verification before routing.

### Product
- `title`, `handle`, `description`, `status (DRAFT/ACTIVE/ARCHIVED)`, `vendor`, `product_type`, `search_vector tsvector (generated)`, `version`.
- `UNIQUE(store_id, handle)`. Index `(store_id, status, created_at DESC)` for listing.
- Concurrency: two staff editing → optimistic lock `version`, 409 on conflict.

### ProductVariant
- `product_id`, `sku`, `title`, `price_minor`, `compare_at_price_minor`, `currency` (= store currency), `option1..3`, `position`, `weight_grams`, `version`.
- `UNIQUE(store_id, sku) WHERE sku IS NOT NULL`, `UNIQUE(product_id, option1, option2, option3)` (NULLS NOT DISTINCT, PG15+), CHECK `price_minor >= 0`.
- Composite FK `(store_id, product_id) → products(store_id, id)`.

### InventoryItem
- One per variant: `variant_id`, `on_hand`, `reserved`, `tracked boolean`, generated `available = on_hand - reserved`.
- CHECK `on_hand >= 0`, `reserved >= 0`, `reserved <= on_hand`. **The database enforces "no oversell".**
- Concurrency: the hottest row in the system. See [06-database.md](06-database.md#inventory-preventing-oversell).

### InventoryReservation
- `inventory_item_id`, `checkout_id`, `quantity`, `status (ACTIVE/COMMITTED/RELEASED/EXPIRED)`, `expires_at`.
- `UNIQUE(checkout_id, inventory_item_id)` makes reserving idempotent per checkout.
- Index `(status, expires_at)` for the expiry sweeper.

### InventoryTransaction (ledger)
- `inventory_item_id`, `delta_on_hand`, `delta_reserved`, `reason (RECEIVED/ADJUSTMENT/RESERVED/RELEASED/SOLD/RETURNED)`, `reference_type/id`, `actor`, `created_at`.
- Append-only. Enables audit and reconciliation (`SUM(delta) = level`).

### Customer
- `store_id`, `email (citext)`, `password_hash NULL` (guest vs. account), `first_name`, `last_name`, `accepts_marketing`.
- `UNIQUE(store_id, email)`. The same person in two stores = two customers (privacy and isolation).

### Cart / CartItem
- Cart: `store_id`, `token_hash` (cart token is a bearer secret; store its hash), `customer_id NULL`, `status (OPEN/CONVERTED/ABANDONED)`, `expires_at`.
- CartItem: `variant_id`, `quantity`, `UNIQUE(cart_id, variant_id)` → adding the same variant twice increments quantity.
- Prices are **not** stored in cart items; they are computed at read time from the catalog (live price). The checkout freezes them.

### Checkout
- `cart_id`, `status (OPEN/COMPLETED/EXPIRED)`, `email`, addresses, `subtotal/discount/shipping/tax/total` snapshot, `reservation_expires_at`, `idempotency_key`, `order_id NULL`.
- `UNIQUE(order_id)`. Partial unique `UNIQUE(cart_id) WHERE status='OPEN'`.

### Order / OrderItem
- Order: `number` (per-store sequence, human friendly), `customer_id NULL`, `email`, `currency`, money totals, `financial_status (PENDING/AUTHORIZED/PAID/PARTIALLY_REFUNDED/REFUNDED/VOIDED)`, `fulfillment_status (UNFULFILLED/PARTIAL/FULFILLED)`, `status (OPEN/CANCELLED/CLOSED)`, `checkout_id UNIQUE`, `version`.
- `UNIQUE(store_id, number)`. Index `(store_id, created_at DESC)`, `(store_id, customer_id)`.
- OrderItem: snapshots `variant_id`, `sku`, `title`, `unit_price_minor`, `quantity`, `total_minor`. Immutable.
- Order numbers: per-store counter row `store_counters(store_id, next_order_number)` updated with `UPDATE ... RETURNING` (row lock, short).

### Payment / PaymentTransaction / Refund
- Payment: `order_id`, `provider`, `provider_payment_id`, `amount_minor`, `currency`, `status`, `captured_minor`, `refunded_minor`, `version`. `UNIQUE(provider, provider_payment_id)`. CHECK `refunded_minor <= captured_minor <= amount_minor`.
- PaymentTransaction: each provider operation (AUTHORIZE/CAPTURE/VOID/REFUND) with `idempotency_key UNIQUE`, `status`, `error_code`, `raw_response jsonb` (redacted).
- Refund: `payment_id`, `amount_minor`, `reason`, `status`, `provider_refund_id UNIQUE`.

### Discount / CouponCode
- Discount: `type (PERCENTAGE/FIXED_AMOUNT/FREE_SHIPPING)`, `value`, `starts_at`, `ends_at`, `usage_limit`, `usage_count`, `once_per_customer`.
- Coupon: `UNIQUE(store_id, code)` case-insensitive.
- Concurrency: usage limit race. Use a conditional update `usage_count = usage_count + 1 WHERE usage_count < usage_limit`. This is the same pattern as inventory.

### Shipment
- `order_id`, `status (PENDING/SHIPPED/DELIVERED/CANCELLED)`, `tracking_number`, items `(order_item_id, quantity)`. Invariant: sum shipped ≤ ordered (checked under order row lock).

### WebhookSubscription / WebhookDelivery
- Subscription: `store_id`, `url`, `topics text[]`, `secret_encrypted`, `previous_secret_encrypted`, `previous_secret_expires_at`, `api_version`, `status`, `app_installation_id NULL`.
- Delivery: `subscription_id`, `event_id`, `topic`, `payload jsonb`, `status (PENDING/SUCCEEDED/FAILED/DEAD)`, `attempt_count`, `next_attempt_at`, `last_status_code`, `last_error`. `UNIQUE(subscription_id, event_id)` = idempotent fan-out.

### ApiKey / OAuthApp / AppInstallation
- ApiKey: `store_id`, `name`, `prefix` (first 8 chars, indexed, displayed), `secret_hash`, `scopes`, `last_used_at`, `revoked_at`. `UNIQUE(prefix)`.
- OAuthApp (platform-level, owned by a developer user): `client_id UNIQUE`, `client_secret_hash`, `redirect_uris`, `requested_scopes`.
- AppInstallation: `store_id`, `app_id`, `granted_scopes`, `status`. `UNIQUE(store_id, app_id)`.

### AuditLog
- `store_id NULL` (platform events), `actor_type (USER/API_KEY/APP/SYSTEM/ADMIN)`, `actor_id`, `action`, `resource_type`, `resource_id`, `changes jsonb (redacted)`, `request_id`, `ip`, `occurred_at`.
- Append-only: the app role gets INSERT/SELECT only. Index `(store_id, occurred_at DESC)`. Partitioned by month later.

### Supporting (infrastructure) tables
`outbox_events`, `jobs`, `idempotency_keys`, `processed_messages` (inbox), `refresh_tokens`, `email_tokens`, `store_counters`. These are described in the relevant sections.
