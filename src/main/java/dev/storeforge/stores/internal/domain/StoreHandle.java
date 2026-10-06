package dev.storeforge.stores.internal.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * URL-safe store identifier used in hostnames ({@code acme.storeforge.localhost}): 3–40 chars of
 * lowercase letters, digits and inner hyphens. The format is also a CHECK constraint in the DB.
 * Reserved handles would otherwise let a merchant impersonate platform hostnames.
 */
public record StoreHandle(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");
    private static final Set<String> RESERVED = Set.of(
            "admin", "api", "app", "auth", "cdn", "docs", "help", "mail", "platform", "shop", "static",
            "status", "store", "storeforge", "support", "www");

    public StoreHandle {
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid handle");
        }
    }

    public boolean isReserved() {
        return RESERVED.contains(value);
    }
}
