package dev.storeforge.shared.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * The tenant identifier. A dedicated type (instead of a bare UUID) makes tenant scoping visible in
 * every signature: a repository method that reads tenant data takes a {@code StoreId}, so "forgot
 * to filter by store" becomes a compile-time question rather than a runtime leak.
 */
public record StoreId(UUID value) {

    public StoreId {
        Objects.requireNonNull(value, "store id");
    }

    public static StoreId of(UUID value) {
        return new StoreId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
