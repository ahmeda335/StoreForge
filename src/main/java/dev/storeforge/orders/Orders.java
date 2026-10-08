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

    /** What a payment for this order must collect; read inside the caller's tenant transaction (MANDATORY). */
    PaymentTarget paymentTarget(StoreId storeId, UUID orderId);

    /**
     * The payment provider confirmed the money (MANDATORY, caller's tenant transaction). Locks the
     * order; if it is still awaiting payment it becomes PAID and its stock is sold, atomically with
     * the caller's payment update. Otherwise (cancelled or expired meanwhile, or already paid
     * another way) nothing changes and the caller must give the money back.
     */
    PaymentOutcome applyPayment(StoreId storeId, UUID orderId, UUID paymentId, String method);

    /** A payment attempt was declined; recorded on the timeline. The order keeps waiting for payment. */
    void recordPaymentFailure(StoreId storeId, UUID orderId, String code);

    /**
     * Money went back to the customer (MANDATORY). A {@code compensation} refund returns money the
     * order never kept (paid after cancellation, paid twice): timeline only, balances unchanged.
     */
    void applyRefund(StoreId storeId, UUID orderId, UUID refundId, long amountMinor, String reason, boolean compensation);

    record PaymentTarget(UUID orderId, long number, String email, long totalMinor, String currency, boolean awaitingPayment) {
    }

    enum PaymentOutcome { PAID, NOT_PAYABLE }

    record ShippingAddress(String name, String line1, @Nullable String line2, String city, @Nullable String region,
                           String postalCode, String country) {
    }

    record PlacedLine(UUID variantId, String productTitle, String variantTitle, @Nullable String sku,
                      long unitPriceMinor, int quantity, long lineTotalMinor) {
    }

    record OrderPlacement(UUID checkoutId, @Nullable UUID customerId, String email, String currency, ShippingAddress address,
                          List<PlacedLine> lines, long subtotalMinor, long discountMinor, @Nullable String discountCode,
                          long shippingMinor, @Nullable String shippingRateName, long taxMinor, long totalMinor) {
    }

    record PlacedOrder(UUID orderId, long number, Instant paymentDueAt) {
    }
}
