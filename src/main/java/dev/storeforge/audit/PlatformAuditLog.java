package dev.storeforge.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Append-only record of what PLATFORM administrators (StoreForge staff) did: sessions, store
 * suspensions, job requeues. Separate from the per-store {@link AuditLog}; an action that affects
 * a store is ALSO written there (actor ADMIN), so the merchant sees it.
 */
public interface PlatformAuditLog {

    /** MANDATORY transaction: the action and its audit entry commit together. */
    void record(UUID adminUserId, String action, @Nullable String targetType, @Nullable String targetId, Map<String, Object> details,
                @Nullable String clientIp);

    List<Entry> recent(int limit);

    record Entry(UUID id, UUID adminUserId, String action, @Nullable String targetType, @Nullable String targetId,
                 Map<String, Object> details, @Nullable String clientIp, Instant occurredAt) {
    }
}
