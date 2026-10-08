package dev.storeforge.inventory.internal.application;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

final class InventoryExceptions {

    private InventoryExceptions() {
    }

    static class VariantNotFound extends StoreForgeException {
        VariantNotFound() {
            super(HttpStatus.NOT_FOUND, "variant_not_found", "Variant not found.");
        }
    }

    static class ItemNotFound extends StoreForgeException {
        ItemNotFound() {
            super(HttpStatus.NOT_FOUND, "inventory_item_not_found", "Inventory item not found.");
        }
    }

    static class BelowReserved extends StoreForgeException {
        BelowReserved(int onHand, int reserved, int delta) {
            super(HttpStatus.CONFLICT, "stock_below_reserved", "Adjusting on-hand " + onHand + " by " + delta
                    + " would leave less than the " + reserved + " units already reserved for customers.");
        }
    }

    static class InvalidAdjustment extends StoreForgeException {
        InvalidAdjustment(String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_adjustment", detail);
        }
    }
}
