package dev.storeforge.payments.internal.provider;

/** Signature missing, wrong, or too old (replay), or an unparseable body. The reason is logged, never returned. */
public class InvalidWebhookException extends RuntimeException {

    public InvalidWebhookException(String reason) {
        super(reason);
    }
}
