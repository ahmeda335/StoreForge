package dev.storeforge.catalog.internal.application;

import dev.storeforge.catalog.internal.domain.Product;
import dev.storeforge.catalog.internal.domain.Variant;
import dev.storeforge.shared.money.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Immutable results returned by catalog services; built inside the transaction, safe to use after it. */
public final class CatalogViews {

    private CatalogViews() {
    }

    public record VariantView(UUID id, String title, @Nullable String sku, Money price, @Nullable Money compareAtPrice,
                              List<String> optionValues, int position, @Nullable Integer weightGrams) {

        static VariantView of(Variant variant, Currency currency) {
            return new VariantView(variant.id(), variant.title(), variant.sku(), variant.price(currency),
                    variant.compareAtPrice(currency), variant.optionValues(), variant.position(), variant.weightGrams());
        }
    }

    public record ProductView(UUID id, String handle, String title, String description, @Nullable String vendor,
                              @Nullable String productType, String status, List<String> optionNames,
                              List<VariantView> variants, long version, Instant createdAt, Instant updatedAt) {

        static ProductView of(Product product, Currency currency) {
            return new ProductView(product.id(), product.handle(), product.title(), product.description(), product.vendor(),
                    product.productType(), product.status().name(), product.optionNames(),
                    product.variants().stream().map(variant -> VariantView.of(variant, currency)).toList(),
                    product.version(), product.createdAt(), product.updatedAt());
        }
    }

    public record ProductSummaryView(UUID id, String handle, String title, String status, @Nullable String vendor,
                                     int variantCount, Money minPrice, Money maxPrice, Instant createdAt, Instant updatedAt) {
    }

    public record StorefrontVariantView(UUID id, String title, Money price, @Nullable Money compareAtPrice, List<String> optionValues) {
    }

    public record StorefrontProductView(UUID id, String handle, String title, String description, @Nullable String vendor,
                                        @Nullable String productType, List<String> optionNames,
                                        List<StorefrontVariantView> variants) {
    }
}
