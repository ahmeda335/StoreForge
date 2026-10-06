package dev.storeforge.shared.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request IDs connect a user-visible error ("request 6f1c...") to the server-side logs.
 *
 * <p>Clients and upstream proxies may send their own {@code X-Request-Id}. We accept it only if it
 * looks like an identifier: an unbounded or arbitrary header value would let a client inject
 * newlines or megabytes of text into every log line (log forging, storage abuse).
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String REQUEST_ATTRIBUTE = RequestIds.class.getName() + ".requestId";

    private static final Pattern ACCEPTED_FORMAT = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private RequestIds() {
    }

    /** Returns the client-supplied ID if it is safe to reuse, otherwise empty. */
    public static Optional<String> acceptIncoming(String headerValue) {
        if (headerValue == null || !ACCEPTED_FORMAT.matcher(headerValue).matches()) {
            return Optional.empty();
        }
        return Optional.of(headerValue);
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
