# Domain Modules

One page per business module, written when the module is implemented: responsibility, aggregates and invariants, tables, public API, events published/consumed, security notes, and tests that protect it.

| Module | Phase | Page |
|---|---|---|
| `shared` (kernel) | P1 | No business logic. See [architecture overview](../architecture/overview.md) |
| `identity` | P2 | [identity.md](identity.md) |
| `notifications` | P2 | Email port; documented in [identity.md](identity.md) until it has its own logic (P9) |
| `stores` (+ `audit`) | P3 | [stores.md](stores.md) |
| `catalog` | P4 | [catalog.md](catalog.md) |
| `inventory` | P5 | [inventory.md](inventory.md) |
| `checkout` + `customers` | P7 | [checkout.md](checkout.md) |
| `orders` | P8 | [orders.md](orders.md) |
| `jobs` + `notifications` | P9 | [jobs.md](jobs.md) |
| `payments` | P10 | [payments.md](payments.md) |
| `webhooks` | P11 | [webhooks.md](webhooks.md) |
| `developer` | P12 | [developer.md](developer.md) |
| `platform` (+ platform MFA in `identity`) | P14 | [platform.md](platform.md) |
| `discounts` | P13 | [discounts.md](discounts.md) |
| `shipping` (+ shipments in `orders`) | P13 | [shipping.md](shipping.md) |
| `storage` (+ product images in `catalog`) | P13 | [storage.md](storage.md) |

Target design for all modules: [blueprint/03-architecture.md](../blueprint/03-architecture.md) and [blueprint/05-domain-model.md](../blueprint/05-domain-model.md).
