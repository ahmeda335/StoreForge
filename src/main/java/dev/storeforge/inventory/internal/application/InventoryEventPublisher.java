package dev.storeforge.inventory.internal.application;

import dev.storeforge.inventory.InventoryEvents;
import dev.storeforge.inventory.internal.persistence.InventoryStore.Item;
import dev.storeforge.jobs.Outbox;
import dev.storeforge.shared.tenancy.StoreId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Physical stock changed: published in the same transaction as the change (transactional outbox). */
@Component
@RequiredArgsConstructor
class InventoryEventPublisher {

    private final Outbox outbox;

    void levelChanged(StoreId storeId, Item item, int delta, String reason) {
        outbox.append(storeId, InventoryEvents.AGGREGATE, item.id().toString(), InventoryEvents.LEVEL_CHANGED, InventoryEvents.VERSION,
                new InventoryEvents.LevelChanged(item.id(), item.variantId(), item.onHand(), item.available(), delta, reason));
    }
}
