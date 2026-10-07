package dev.storeforge.payments.internal.provider;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * The provider could not be reached or failed (timeout, 5xx, 429, circuit open). Retry later with
 * the SAME idempotency key. {@code outcomeUnknown}: the request may have been performed (read
 * timeout, 5xx), so "failed" must never be shown to the customer, only "processing".
 */
@Getter
public class ProviderUnavailableException extends RuntimeException {

    private final String operation;
    private final boolean outcomeUnknown;

    public ProviderUnavailableException(String operation, boolean outcomeUnknown, String message, @Nullable Throwable cause) {
        super(operation + ": " + message, cause);
        this.operation = operation;
        this.outcomeUnknown = outcomeUnknown;
    }
}
