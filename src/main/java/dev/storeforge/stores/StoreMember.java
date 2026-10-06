package dev.storeforge.stores;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.Set;
import java.util.UUID;

/**
 * An authenticated user's membership in one store, as resolved by {@link StoreAccess}. Owners
 * implicitly hold every permission, including permissions added to the catalog later.
 */
public record StoreMember(StoreId storeId, UUID userId, UUID roleId, String roleName, boolean owner,
                          Set<Permission> permissions) {

    public StoreMember {
        permissions = Set.copyOf(permissions);
    }

    public boolean has(Permission permission) {
        return owner || permissions.contains(permission);
    }

    /**
     * Anti-escalation: a member may hand out (or act on) a set of permissions only if they hold
     * all of it themselves. Without this, anyone with staff:manage could create an all-powerful
     * role and assign it to themselves.
     */
    public boolean covers(boolean otherIsOwner, Set<Permission> otherPermissions) {
        if (owner) {
            return true;
        }
        return !otherIsOwner && permissions.containsAll(otherPermissions);
    }
}
