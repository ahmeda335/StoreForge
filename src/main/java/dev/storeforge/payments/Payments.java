package dev.storeforge.payments;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface Payments {

    /**
     * Starts (or resumes) the payment of an order: the amount comes from the order, never from the
     * client. Safe to call repeatedly: one payment per order, and provider calls are idempotent.
     * NOT to be called inside a transaction: it calls the provider over the network.
     */
    PaymentSession start(StoreId storeId, UUID orderId);

    Optional<PaymentSession> find(StoreId storeId, UUID orderId);

    /** What the storefront needs to show the card form ({@code clientSecret}) or the payment state. */
    record PaymentSession(UUID paymentId, String provider, String status, long amountMinor, String currency,
                          @Nullable String clientSecret, @Nullable String failureCode) {
    }
}
