# Merchant Admin API: stores and staff (implemented, P3)

All endpoints need `Authorization: Bearer <access token>`. Errors are Problem Details ([conventions](conventions.md)). `{storeId}`-scoped endpoints return **404 `store_not_found`** to non-members and **403 `permission_denied`** to members without the permission. Try them with [`http/stores.http`](../../http/stores.http).

| Method & path | Permission | Body | Success | Notable errors |
|---|---|---|---|---|
| `POST /admin/v1/stores` | (any user) | `{handle, name, currency}` | `201` + `Location`; caller becomes owner | `handle_unavailable` (409: taken or reserved), `invalid_handle` (422), `validation_failed` |
| `GET /admin/v1/stores` | — | — | `200 {data: [{id, handle, name, status}]}`: stores I belong to | — |
| `GET /admin/v1/stores/{storeId}` | member | — | `200 {id, handle, name, status, currency, createdAt}` | — |
| `PATCH /admin/v1/stores/{storeId}` | `store:manage` | `{name}` | `200` | — |
| `GET …/{storeId}/members` | `staff:read` | — | `200 {data: [{userId, email, roleId, roleName, owner, joinedAt}]}` | — |
| `PATCH …/{storeId}/members/{userId}` | `staff:manage` | `{roleId}` | `204` | `member_not_found`, `role_not_found`, `permission_denied` (escalation), `last_owner` (409) |
| `DELETE …/{storeId}/members/{userId}` | `staff:manage` (or yourself: leave) | — | `204` | `last_owner`, `permission_denied` |
| `GET …/{storeId}/roles` | `staff:read` | — | `200 {data: [{id, name, owner, permissions[]}]}` | — |
| `POST …/{storeId}/roles` | `roles:manage` | `{name, permissions[]}` | `201` | `unknown_permission` (422), `role_name_taken` (409), `permission_denied` |
| `DELETE …/{storeId}/roles/{roleId}` | `roles:manage` | — | `204` | `role_in_use`, `role_protected` (409) |
| `GET …/{storeId}/invitations` | `staff:read` | — | `200` pending invitations | — |
| `POST …/{storeId}/invitations` | `staff:manage` | `{email, roleId}` | `201 {id, email, roleId, expiresAt}`; email sent | `already_member`, `role_not_found`, `permission_denied` |
| `DELETE …/{storeId}/invitations/{id}` | `staff:manage` | — | `204` | `invitation_not_found` |
| `POST …/{storeId}/invitations/acceptance` | (invited user; not yet a member) | `{token}` | `204` | `invitation_not_found` (wrong user/expired/used), `already_member` |
| `GET …/{storeId}/audit-log?limit=&cursor=` | `audit:read` | — | `200 {data: [...], page: {nextCursor, hasMore}}` | `invalid_cursor` (400) |

**Permissions:** `store:manage`, `staff:read`, `staff:manage`, `roles:manage`, `audit:read`, `products:read|write`, `inventory:read|write`, `orders:read|write|refund`, `customers:read`. New stores get the **Owner** role (all permissions, now and future) and a **Staff** role (`products:*`, `inventory:*`, `orders:read`).

**Invitation link:** `{APP_BASE_URL}/accept-invitation#store=<storeId>&token=<token>` (valid 7 days). The invitee must sign in with the invited email address.

**Pagination:** keyset cursors, `limit` default 25, max 100 (larger values are clamped).
