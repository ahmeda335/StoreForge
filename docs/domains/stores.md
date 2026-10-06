# Module: stores (P3) and audit

**Responsibility:** tenants (stores), their hostnames, staff memberships, roles/permissions and invitations. It is the **authorization decision point** for every tenant-scoped module.

**Public API** (`dev.storeforge.stores`):

| Type | Purpose |
|---|---|
| `StoreAccess.require(userId, storeId, Permission...)` | Returns the caller's `StoreMember`, or throws `StoreNotFoundException` (404) / `PermissionDeniedException` (403). Must run inside `TenantTransactions.inStore` |
| `StoreMember` | `roleName`, `owner`, `permissions`, `has(p)`, `covers(...)` for anti-escalation |
| `Permission` | Fixed catalog `resource:action` |

**Depends on:** `shared` (tenancy, pagination), `identity` (`UserDirectory`), `audit` (`AuditLog`), `notifications` (`EmailSender`).

The **audit** module exposes `AuditLog.recordUserAction(...)` and `list(...)`. Both are `Propagation.MANDATORY`: they must join the caller's tenant transaction.

## Data

| Table | Kind | Notes |
|---|---|---|
| `stores` | Directory | `uq_stores_handle` (citext), handle format CHECK, status CHECK |
| `store_domains` | Directory | `uq_store_domains_hostname` globally; one primary per store (partial unique index). Platform subdomain `{handle}.storeforge.localhost` created with the store |
| `store_members` | Directory | PK `(store_id, user_id)`; composite FK `(store_id, role_id) → roles(store_id, id)` |
| `roles` | **RLS** | `uq_roles_store_name`, `uq_roles_store_id` (composite FK target), one owner role per store; `permissions text[]` |
| `invitations` | **RLS** | Token hashed; one pending invitation per email per store (partial unique index) |
| `audit_log` | **RLS** | App role has INSERT/SELECT only (UPDATE/DELETE revoked) |

## Rules (invariants)

| Rule | Enforced by |
|---|---|
| Members of A can't see or touch B | Path store + membership check (404); RLS on tenant data; composite FKs |
| ≥ 1 owner per store | `SELECT ... FROM stores FOR UPDATE` then owner count, in `MemberService` (write-skew safe, [EXP-003](../experiments/EXP-003-write-skew-last-owner.md)) |
| No privilege escalation | `StoreMember.covers`: a member can only grant, assign, invite into, remove, or create roles with permissions they hold; only owners touch owners |
| Owner role always has every permission | Stored as an empty array with `is_owner`; expanded in code (future permissions apply automatically) |
| A role in use can't be deleted | `fk_store_members_role` → 409 `role_in_use` (the database decides, not a racy pre-check) |
| Invitations: invited email only, single use, newest only | Conditional `UPDATE ... RETURNING` in the tenant context + email match (failures roll back the consumption) |
| Every change is audited atomically | `AuditLog` joins the business transaction (`MANDATORY`) |

## Request flow (tenant-scoped endpoint)

```mermaid
sequenceDiagram
    participant C as Client (Bearer token)
    participant S as Security chain /admin/**
    participant SV as Application service
    participant TT as TenantTransactions
    participant DB as PostgreSQL
    C->>S: PATCH /admin/v1/stores/{storeId}/members/{userId}
    S->>S: JWT valid → UserPrincipal (authentication ends)
    S->>SV: changeRole(userId, storeId, ...)
    SV->>TT: inStore(storeId, work)
    TT->>DB: BEGIN; set_config('app.current_store_id', storeId, true)
    SV->>DB: membership ⋈ roles (RLS: only this store's roles)
    alt not a member
        SV-->>C: 404 store_not_found (ROLLBACK)
    else missing staff:manage / escalation
        SV-->>C: 403 permission_denied
    end
    SV->>DB: SELECT ... FROM stores WHERE id = ? FOR UPDATE
    SV->>DB: owner count · UPDATE store_members · INSERT audit_log
    TT->>DB: COMMIT (tenant setting disappears)
    SV-->>C: 204
```

## Known limitations
- Store suspension and closing exist as statuses, but there are no endpoints yet (platform admin, P14).
- Custom domains and storefront host resolution come in P4.
- Membership is looked up on every request (no cache yet).
- Invitation emails have the same non-durable delivery as identity emails (P9).
- No per-user store-creation quota yet (abuse control, P14).
