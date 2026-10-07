package dev.storeforge.payments.internal.provider;

import lombok.Getter;

/** A definitive refusal (card declined, amount too large, invalid state). Retrying won't help. */
@Getter
public class ProviderRejectedException extends RuntimeException {

    private final String code;

    public ProviderRejectedException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }
}
