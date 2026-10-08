package dev.storeforge.stores;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.UUID;

/**
 * Authorization decision point for tenant-scoped operations. Authentication (who is calling) is
 * already done; this answers "is that user a member of THIS store, with THESE permissions?".
 *
 * <p>Must be called inside a tenant transaction for {@code storeId}
 * ({@code TenantTransactions.inStore}): roles are tenant data protected by row-level security.
 *
 * <p>Non-members get {@link StoreNotFoundException} (404), never 403. A 403 would confirm that the
 * store exists to someone who has no business knowing.
 */
public interface StoreAccess {

    /** @throws StoreNotFoundException if not a member; {@link PermissionDeniedException} if a permission is missing */
    StoreMember require(UUID userId, StoreId storeId, Permission... permissions);

    /**
     * Same decision for any actor. Users: as above. API clients: 404 for any store but their own,
     * 403 for a permission their scopes don't grant. Also requires a tenant transaction.
     */
    void require(Actor actor, StoreId storeId, Permission... permissions);
}
