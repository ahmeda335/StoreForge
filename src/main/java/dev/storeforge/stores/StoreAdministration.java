package dev.storeforge.stores;

import dev.storeforge.shared.tenancy.StoreId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Platform-level operations on stores (used by the platform API, P14). Every change is written to
 * the platform audit log AND to the store's own audit log (actor ADMIN): merchants see what the
 * platform did to their store.
 */
public interface StoreAdministration {

    record StoreSummary(StoreId id, String handle, String name, String status, String currency, Instant createdAt) {
    }

    List<StoreSummary> list(@Nullable String query, @Nullable String status, int limit);

    StoreSummary suspend(StoreId storeId, UUID adminUserId, String reason, @Nullable String clientIp);

    StoreSummary reinstate(StoreId storeId, UUID adminUserId, String reason, @Nullable String clientIp);
}
