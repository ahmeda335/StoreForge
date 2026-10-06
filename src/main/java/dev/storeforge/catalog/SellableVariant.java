package dev.storeforge.catalog;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Current catalog data for one variant. Prices are minor units in the store's currency. */
public record SellableVariant(UUID variantId, UUID productId, String productTitle, String variantTitle,
                              @Nullable String sku, long priceMinor, boolean sellable) {
}
