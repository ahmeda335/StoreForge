package dev.storeforge.orders;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Public contract of the events the orders module publishes through the outbox (aggregate type
 * {@code order}, aggregate id = order id). Payloads are thin: ids and the values consumers route on.
 * Changing a payload incompatibly means a new event VERSION, never an in-place change.
 */
public final class OrderEvents {

    public static final String AGGREGATE = "order";
    public static final String PLACED = "OrderPlaced";
    public static final String PAID = "OrderPaid";
    public static final String CANCELLED = "OrderCancelled";
    public static final String FULFILLED = "OrderFulfilled";
    public static final String REFUNDED = "OrderRefunded";
    public static final String SHIPPED = "OrderShipped";
    public static final int VERSION = 1;

    private OrderEvents() {
    }

    public record Placed(UUID orderId, long number, long totalMinor, String currency, @Nullable UUID customerId) {
    }

    public record Paid(UUID orderId, long number, long totalMinor, String currency, @Nullable UUID customerId, String method) {
    }

    public record Cancelled(UUID orderId, long number, String reason) {
    }

    public record Fulfilled(UUID orderId, long number) {
    }

    public record Shipped(UUID orderId, long number, UUID shipmentId, @Nullable String carrier, @Nullable String trackingNumber,
                          boolean complete) {
    }

    public record Refunded(UUID orderId, long number, UUID refundId, long amountMinor, String currency, long refundedTotalMinor) {
    }
}
