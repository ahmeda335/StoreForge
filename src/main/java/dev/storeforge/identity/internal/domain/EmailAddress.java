package dev.storeforge.identity.internal.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalized email address (trimmed, lower-cased). Normalization happens in exactly one place so
 * that "Alice@Example.com" and "alice@example.com " are the same account everywhere: uniqueness,
 * login, rate-limit keys.
 *
 * <p>The database column is {@code citext} as a second line of defense for uniqueness, but queries
 * bind parameters as text, and {@code citext = text} compares case-SENSITIVELY. Normalizing in the
 * application is therefore required, not optional.
 */
public record EmailAddress(String value) {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public EmailAddress {
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a valid email address");
        }
    }

    public static EmailAddress of(String raw) {
        return new EmailAddress(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
