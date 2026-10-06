package dev.storeforge.shared.ratelimit;

import dev.storeforge.shared.error.StoreForgeException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends StoreForgeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests. Retry later.");
        this.retryAfter = retryAfter;
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        // Round up: telling a client to retry after 0 seconds invites an immediate retry storm.
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
        return headers;
    }
}
