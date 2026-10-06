package dev.storeforge.audit;

import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface AuditLog {

    /** Records an action by a user. Must be called inside a tenant transaction for {@code storeId}. */
    void recordUserAction(StoreId storeId, UUID userId, String action, String resourceType,
                          @Nullable String resourceId, Map<String, Object> details);

    /** Newest first, keyset-paginated. Must be called inside a tenant transaction for {@code storeId}. */
    Page<AuditEntry> list(StoreId storeId, int limit, @Nullable Cursor after);
}
