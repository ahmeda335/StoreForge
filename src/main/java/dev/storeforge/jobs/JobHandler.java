package dev.storeforge.jobs;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Executes jobs of one type. Same contract as {@link EventHandler}: at-least-once, idempotent, own transactions. */
public interface JobHandler {

    String type();

    void handle(Job job);

    record Job(UUID id, @Nullable StoreId storeId, String type, JsonNode payload, int attempt) {
    }
}
