package dev.storeforge.stores;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.Optional;

/**
 * Lookups on the tenant directory. Results may be cached for a short time (see implementation), so
 * changes such as suspension or a new domain take effect within that TTL, not instantly.
 */
public interface StoreDirectory {

    Optional<StoreInfo> find(StoreId storeId);

    /** Resolves a request hostname ({@code acme.storeforge.localhost}) to its store. Port must already be stripped. */
    Optional<StoreInfo> resolveHost(String hostname);
}
