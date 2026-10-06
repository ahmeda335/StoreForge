/**
 * Stores: the tenants of the platform, their staff memberships, roles and invitations.
 *
 * <p>Public API: {@link dev.storeforge.stores.StoreAccess}, the single authorization decision
 * point every tenant-scoped module calls ("may this user do X in store S?"), plus
 * {@link dev.storeforge.stores.Permission} and {@link dev.storeforge.stores.StoreMember}.
 */
@ApplicationModule(displayName = "Stores & Tenancy")
package dev.storeforge.stores;

import org.springframework.modulith.ApplicationModule;
