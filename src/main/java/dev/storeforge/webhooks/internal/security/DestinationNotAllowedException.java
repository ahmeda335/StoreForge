package dev.storeforge.webhooks.internal.security;

import java.net.UnknownHostException;

/**
 * The URL (or what its host resolves to right now) is not a public internet destination. Extends
 * {@link UnknownHostException} so the HTTP client's DNS resolver can refuse to connect with it.
 */
public class DestinationNotAllowedException extends UnknownHostException {

    public DestinationNotAllowedException(String reason) {
        super(reason);
    }
}
