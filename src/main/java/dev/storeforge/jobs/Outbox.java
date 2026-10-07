package dev.storeforge.jobs;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The transactional outbox. Appending is an INSERT in the caller's transaction (MANDATORY): the event
 * exists if and only if the business change committed. That solves the dual-write problem ("order
 * saved but event lost" / "event sent for an order that rolled back"). A relay later turns events
 * into jobs for every subscribed {@link EventHandler}.
 *
 * <p>Keep payloads thin: ids and the few values consumers need for routing. Consumers re-read tenant
 * data inside a tenant transaction.
 */
public interface Outbox {

    UUID append(@Nullable StoreId storeId, String aggregateType, String aggregateId, String eventType, int eventVersion,
                Object payload);
}
