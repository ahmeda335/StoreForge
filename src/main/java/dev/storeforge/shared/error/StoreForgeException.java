package dev.storeforge.shared.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Base class for expected, business-meaningful failures ("invalid credentials", "insufficient
 * stock"). Each carries the HTTP status and the stable machine-readable {@code code} that ends up in
 * the Problem Details response. Unexpected failures must NOT extend this: they become opaque 500s.
 */
public abstract class StoreForgeException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected StoreForgeException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Extra response headers (e.g. {@code Retry-After}). */
    public HttpHeaders headers() {
        return new HttpHeaders();
    }
}
