package dev.storeforge.inventory;

import java.util.UUID;

/**
 * Events the inventory module publishes through the outbox (aggregate {@code inventory_item}).
 * Only PHYSICAL stock changes (adjustments, sales) are published; reservations change
 * {@code available} on every checkout and are transient, so they are not.
 */
public final class InventoryEvents {

    public static final String AGGREGATE = "inventory_item";
    public static final String LEVEL_CHANGED = "InventoryLevelChanged";
    public static final int VERSION = 1;

    private InventoryEvents() {
    }

    public record LevelChanged(UUID inventoryItemId, UUID variantId, int onHand, int available, int delta, String reason) {
    }
}
