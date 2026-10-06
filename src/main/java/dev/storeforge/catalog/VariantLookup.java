package dev.storeforge.catalog;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog facts other modules need to sell a variant (checkout pricing, order snapshots). Must run
 * inside the caller's tenant transaction. Variants that don't exist in the store are absent from
 * the result; variants of non-ACTIVE products are present with {@code sellable = false}.
 */
public interface VariantLookup {

    Map<UUID, SellableVariant> find(StoreId storeId, Collection<UUID> variantIds);
}
