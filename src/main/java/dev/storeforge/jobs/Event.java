package dev.storeforge.jobs;

import dev.storeforge.shared.tenancy.StoreId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** An outbox event as delivered to an {@link EventHandler}. {@code eventId} is the deduplication key. */
public record Event(UUID eventId, @Nullable StoreId storeId, String aggregateType, String aggregateId, String eventType,
                    int eventVersion, Instant occurredAt, JsonNode payload) {
}
