package dev.storeforge.inventory;

import dev.storeforge.shared.error.StoreForgeException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Not enough available stock for (at least) one line. Nothing was reserved: reservations are all-or-nothing. */
public class InsufficientStockException extends StoreForgeException {

    private final UUID variantId;

    public InsufficientStockException(UUID variantId, int available, int requested) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient_stock",
                "Variant " + variantId + " has " + Math.max(available, 0) + " available, " + requested + " requested.");
        this.variantId = variantId;
    }

    public UUID variantId() {
        return variantId;
    }
}
