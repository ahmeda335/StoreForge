package dev.storeforge.orders;

import dev.storeforge.shared.tenancy.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface Orders {

    /**
     * How long stock must be held for a new order: the payment window plus a grace period. The
     * ORDER expires first (when its payment window ends) and releases the stock itself; the
     * reservation's own, later expiry is only a safety net for flows that crashed (ADR-014).
     */
    Duration reservationHold();

    /** Creates the order inside the caller's tenant transaction (MANDATORY). One order per checkout. */
    PlacedOrder place(StoreId storeId, OrderPlacement placement);

    record ShippingAddress(String name, String line1, @Nullable String line2, String city, @Nullable String region,
                           String postalCode, String country) {
    }

    record PlacedLine(UUID variantId, String productTitle, String variantTitle, @Nullable String sku,
                      long unitPriceMinor, int quantity, long lineTotalMinor) {
    }

    record OrderPlacement(UUID checkoutId, @Nullable UUID customerId, String email, String currency, ShippingAddress address,
                          List<PlacedLine> lines, long subtotalMinor, long shippingMinor, long taxMinor, long totalMinor) {
    }

    record PlacedOrder(UUID orderId, long number, Instant paymentDueAt) {
    }
}
