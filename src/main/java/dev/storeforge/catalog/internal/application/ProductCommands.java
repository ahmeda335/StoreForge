package dev.storeforge.catalog.internal.application;

import dev.storeforge.catalog.internal.domain.ProductStatus;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Inputs of catalog use cases, independent of HTTP. Prices are decimal strings in the store's currency. */
public final class ProductCommands {

    private ProductCommands() {
    }

    public record NewVariant(@Nullable String sku, String price, @Nullable String compareAtPrice,
                             List<String> optionValues, @Nullable Integer weightGrams) {
    }

    /** Partial update of a variant: null means "keep the current value". */
    public record VariantChanges(@Nullable String sku, @Nullable String price, @Nullable String compareAtPrice,
                                 @Nullable List<String> optionValues, @Nullable Integer weightGrams) {
    }

    public record NewProduct(@Nullable String handle, String title, @Nullable String description, @Nullable String vendor,
                             @Nullable String productType, @Nullable ProductStatus status, List<String> optionNames,
                             List<NewVariant> variants) {
    }

    /** Partial update: null means "keep the current value". */
    public record ProductChanges(@Nullable String handle, @Nullable String title, @Nullable String description,
                                 @Nullable String vendor, @Nullable String productType, @Nullable ProductStatus status) {
    }
}
