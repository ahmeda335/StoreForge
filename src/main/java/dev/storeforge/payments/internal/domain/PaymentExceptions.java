package dev.storeforge.payments.internal.domain;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

public final class PaymentExceptions {

    private PaymentExceptions() {
    }

    public static class PaymentNotFound extends StoreForgeException {
        public PaymentNotFound() {
            super(HttpStatus.NOT_FOUND, "payment_not_found", "This order has no payment.");
        }
    }

    public static class OrderNotAwaitingPayment extends StoreForgeException {
        public OrderNotAwaitingPayment() {
            super(HttpStatus.CONFLICT, "order_not_awaiting_payment", "This order is not waiting for a payment (paid or cancelled).");
        }
    }

    public static class PaymentClosed extends StoreForgeException {
        public PaymentClosed() {
            super(HttpStatus.CONFLICT, "payment_closed", "The payment of this order was cancelled or failed.");
        }
    }

    /**
     * The provider is unreachable or the outcome of our call is unknown. Never "failed": the same
     * request can be retried safely (idempotency key), and the outcome will be reconciled.
     */
    public static class ProviderUnavailable extends StoreForgeException {
        public ProviderUnavailable() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "payment_provider_unavailable",
                    "The payment provider is not responding. Please retry in a few seconds; you will not be charged twice.");
        }

        @Override
        public HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "5");
            return headers;
        }
    }

    public static class PaymentRejected extends StoreForgeException {
        public PaymentRejected(String providerCode) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "payment_rejected", "The payment provider rejected the payment (" + providerCode + ").");
        }
    }

    public static class NotRefundable extends StoreForgeException {
        public NotRefundable() {
            super(HttpStatus.CONFLICT, "payment_not_refundable", "Only captured payments can be refunded.");
        }
    }

    public static class RefundExceedsAvailable extends StoreForgeException {
        public RefundExceedsAvailable(String available) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "refund_exceeds_available",
                    "The refund exceeds what can still be refunded (" + available + ", including refunds in progress).");
        }
    }

    public static class InvalidAmount extends StoreForgeException {
        public InvalidAmount() {
            super(HttpStatus.BAD_REQUEST, "invalid_amount", "The amount must be a positive decimal in the store currency.");
        }
    }

    public static class InvalidWebhook extends StoreForgeException {
        public InvalidWebhook() {
            super(HttpStatus.BAD_REQUEST, "invalid_webhook", "Webhook signature verification failed.");
        }
    }

    public static class UnknownProvider extends StoreForgeException {
        public UnknownProvider() {
            super(HttpStatus.NOT_FOUND, "not_found", "Not found.");
        }
    }
}
