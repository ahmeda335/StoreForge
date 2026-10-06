# RBAC and Privilege Escalation

**Problem.** Stores have several people with different jobs. "Everyone can do everything" is unsafe, and hard-coded `if (isOwner)` checks don't scale. Any system that lets users *manage* permissions also has to stop them from giving themselves more.

**Why we need it.** Staff, apps (scopes in P12) and platform admins (P14) all need permission checks that are fine-grained, per store, and reviewable.

**Alternatives.** Fixed roles only (simple, inflexible); ABAC/policy engines like OPA or Cedar (powerful, more to learn; consider when rules depend on attributes such as "orders under $100"); ACLs per resource (fine for document sharing, heavy for commerce).

**Decision.** RBAC per store: a code-defined permission catalog (`resource:action`), per-store roles (Owner, Staff, custom), one role per membership, plus anti-escalation rules.

**Mental model.**
- **Authentication ≠ authorization.** The JWT proves *who*. `StoreAccess.require(user, store, permission)` decides *whether*, for one store, at call time (permissions are never baked into tokens).
- **Check in the application service, not only the controller.** Jobs, admin tools and future APIs call services too.
- **404 vs 403:** non-members get 404 (the store's existence is itself information). Members missing a permission get 403 (they know the store exists).
- **Anti-escalation (`StoreMember.covers`):** you can only hand out, assign, invite into, remove, or define roles whose permissions are a **subset of yours**. Only owners act on owners. Without this rule, `staff:manage` is effectively `owner`: create a super-role, assign it to yourself.
- **Owner = all permissions, implicitly.** It is stored as a flag, not a list, so permissions added to the catalog later automatically belong to owners. Custom roles never auto-grow.
- **The permission catalog lives in code** because only code can *enforce* a permission. A merchant-defined permission that nothing checks would be a false promise.

**Project usage.** `Permission`, `StoreMember`, `StoreAccess` / `DefaultStoreAccess`, `MemberService`, `RoleService`, `InvitationService`.

**Failure cases.** A new endpoint forgetting `require(...)`: the isolation suite catches cross-store access, but not missing permission checks within a store. That needs per-endpoint permission tests (`StaffManagementIT.permissionsAreEnforcedPerEndpoint` pattern). Permissions defined but never enforced: keep the catalog small. Role changes take effect on the next request, because there is no caching yet. With a cache, its TTL becomes the revocation delay.

**Testing.** `StaffManagementIT`: escalation attempts (inviting into Owner/Staff, demoting an owner, self-promotion) are 403; per-endpoint permission denials; last-owner rules; unknown permission codes rejected.

**Observability.** Audit entries for every role/member/invitation change (`member.role_changed` with from/to). 403 rates in HTTP metrics.

**When NOT to use it.** Don't express business rules ("refunds over $500 need a manager") as more roles. That's an attribute-based rule in the domain service.
