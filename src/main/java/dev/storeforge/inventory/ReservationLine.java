package dev.storeforge.inventory;

import java.util.UUID;

public record ReservationLine(UUID variantId, int quantity) {

    public ReservationLine {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
