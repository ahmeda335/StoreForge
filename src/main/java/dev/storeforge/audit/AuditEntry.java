package dev.storeforge.audit;

import dev.storeforge.shared.tenancy.StoreId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One audited action. {@code details} must never contain secrets or tokens; prefer ids and the
 * changed field names/values that matter for "who changed what".
 */
public record AuditEntry(
        UUID id,
        StoreId storeId,
        ActorType actorType,
        @Nullable UUID actorId,
        String action,
        String resourceType,
        @Nullable String resourceId,
        Map<String, Object> details,
        @Nullable String requestId,
        Instant occurredAt) {

    public enum ActorType { USER, API_KEY, APP, SYSTEM, ADMIN }
}
