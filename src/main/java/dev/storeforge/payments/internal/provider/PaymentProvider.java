package dev.storeforge.payments.internal.provider;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Port to a payment provider, in OUR vocabulary. Every mutating call carries an idempotency key
 * derived from our own stable ids, so a retry after a timeout can never charge or refund twice.
 *
 * <p>Failures: {@link ProviderUnavailableException} (try again later with the same key; the
 * outcome may be unknown), {@link ProviderRejectedException} (a definitive no: retrying won't help).
 */
public interface PaymentProvider {

    String name();

    ProviderPayment createPayment(CreatePayment request, String idempotencyKey);

    ProviderPayment retrievePayment(String providerPaymentId);

    ProviderPayment cancelPayment(String providerPaymentId, String idempotencyKey);

    ProviderRefund refund(String providerPaymentId, long amountMinor, UUID storeId, UUID refundId, String idempotencyKey);

    ProviderRefund retrieveRefund(String providerRefundId);

    /** Verifies the signature over the RAW body, then parses. @throws InvalidWebhookException */
    ProviderEvent parseWebhook(String rawBody, @Nullable String signatureHeader);

    record CreatePayment(UUID storeId, UUID paymentId, UUID orderId, long orderNumber, long amountMinor, String currency) {
    }

    enum PaymentStatus { REQUIRES_ACTION, PROCESSING, SUCCEEDED, CANCELED }

    enum RefundStatus { PENDING, SUCCEEDED, FAILED }

    record ProviderPayment(String id, @Nullable String clientSecret, PaymentStatus status, long amountMinor,
                           @Nullable String failureCode) {
    }

    record ProviderRefund(String id, RefundStatus status) {
    }

    /**
     * A provider notification, normalized. {@code storeId}, {@code paymentId} and {@code refundId}
     * come from the metadata WE attached when creating the object; they are trustworthy because the
     * whole event is signed by the provider.
     */
    record ProviderEvent(String id, EventType type, @Nullable UUID storeId, @Nullable UUID paymentId,
                         @Nullable String providerPaymentId, @Nullable UUID refundId, @Nullable String providerRefundId,
                         @Nullable RefundStatus refundStatus, @Nullable String failureCode) {
    }

    enum EventType { PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_CANCELED, REFUND_UPDATED, IGNORED }
}
