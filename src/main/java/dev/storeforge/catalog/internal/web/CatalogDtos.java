package dev.storeforge.catalog.internal.web;

import dev.storeforge.catalog.internal.application.CatalogViews.ProductSummaryView;
import dev.storeforge.catalog.internal.application.CatalogViews.ProductView;
import dev.storeforge.catalog.internal.application.CatalogViews.StorefrontImageView;
import dev.storeforge.catalog.internal.application.CatalogViews.StorefrontProductView;
import dev.storeforge.catalog.internal.application.CatalogViews.VariantView;
import dev.storeforge.catalog.internal.domain.ProductStatus;
import dev.storeforge.shared.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class CatalogDtos {

    static final String AMOUNT = "\\d{1,12}(\\.\\d{1,4})?";

    private CatalogDtos() {
    }

    /** Money on the wire: a decimal STRING (JSON numbers are floats in many clients) plus the currency. */
    record MoneyDto(String amount, String currency) {
        static @Nullable MoneyDto of(@Nullable Money money) {
            return money == null ? null : new MoneyDto(money.toDecimalString(), money.currency().getCurrencyCode());
        }
    }

    record VariantRequest(
            @Nullable @Size(max = 100) String sku,
            @NotBlank @Pattern(regexp = AMOUNT, message = "must be a decimal amount like 19.99") String price,
            @Nullable @Pattern(regexp = AMOUNT, message = "must be a decimal amount like 24.99") String compareAtPrice,
            @NotNull @Size(max = 3) List<@NotBlank @Size(max = 100) String> options,
            @Nullable @PositiveOrZero Integer weightGrams) {
    }

    record VariantPatchRequest(
            @Nullable @Size(max = 100) String sku,
            @Nullable @Pattern(regexp = AMOUNT, message = "must be a decimal amount like 19.99") String price,
            @Nullable @Pattern(regexp = AMOUNT, message = "must be a decimal amount like 24.99") String compareAtPrice,
            @Nullable @Size(max = 3) List<@NotBlank @Size(max = 100) String> options,
            @Nullable @PositiveOrZero Integer weightGrams) {
    }

    record CreateProductRequest(
            @Nullable @Size(max = 100) String handle,
            @NotBlank @Size(max = 255) String title,
            @Nullable @Size(max = 20_000) String description,
            @Nullable @Size(max = 100) String vendor,
            @Nullable @Size(max = 100) String productType,
            @Nullable ProductStatus status,
            @NotNull @Size(max = 3) List<@NotBlank @Size(max = 50) String> options,
            @NotNull @Size(min = 1, max = 100) List<@Valid VariantRequest> variants) {
    }

    record UpdateProductRequest(
            @Nullable @Size(max = 100) String handle,
            @Nullable @Size(min = 1, max = 255) String title,
            @Nullable @Size(max = 20_000) String description,
            @Nullable @Size(max = 100) String vendor,
            @Nullable @Size(max = 100) String productType,
            @Nullable ProductStatus status) {
    }

    record VariantResponse(UUID id, String title, @Nullable String sku, MoneyDto price, @Nullable MoneyDto compareAtPrice,
                           List<String> options, int position, @Nullable Integer weightGrams) {
        static VariantResponse of(VariantView view) {
            return new VariantResponse(view.id(), view.title(), view.sku(), MoneyDto.of(view.price()),
                    MoneyDto.of(view.compareAtPrice()), view.optionValues(), view.position(), view.weightGrams());
        }
    }

    record ProductResponse(UUID id, String handle, String title, String description, @Nullable String vendor,
                           @Nullable String productType, String status, List<String> options,
                           List<VariantResponse> variants, long version, Instant createdAt, Instant updatedAt) {
        static ProductResponse of(ProductView view) {
            return new ProductResponse(view.id(), view.handle(), view.title(), view.description(), view.vendor(),
                    view.productType(), view.status(), view.optionNames(),
                    view.variants().stream().map(VariantResponse::of).toList(), view.version(), view.createdAt(), view.updatedAt());
        }
    }

    record ProductSummaryResponse(UUID id, String handle, String title, String status, @Nullable String vendor,
                                  int variantCount, MoneyDto minPrice, MoneyDto maxPrice, Instant createdAt, Instant updatedAt) {
        static ProductSummaryResponse of(ProductSummaryView view) {
            return new ProductSummaryResponse(view.id(), view.handle(), view.title(), view.status(), view.vendor(),
                    view.variantCount(), MoneyDto.of(view.minPrice()), MoneyDto.of(view.maxPrice()), view.createdAt(), view.updatedAt());
        }
    }

    record StorefrontVariantResponse(UUID id, String title, MoneyDto price, @Nullable MoneyDto compareAtPrice, List<String> options,
                                     boolean available) {
    }

    record StorefrontProductResponse(UUID id, String handle, String title, String description, @Nullable String vendor,
                                     @Nullable String productType, List<String> options, List<StorefrontVariantResponse> variants,
                                     List<StorefrontImageView> images) {
        static StorefrontProductResponse of(StorefrontProductView view) {
            return new StorefrontProductResponse(view.id(), view.handle(), view.title(), view.description(), view.vendor(),
                    view.productType(), view.optionNames(), view.variants().stream()
                    .map(v -> new StorefrontVariantResponse(v.id(), v.title(), MoneyDto.of(v.price()), MoneyDto.of(v.compareAtPrice()),
                            v.optionValues(), v.available())).toList(), view.images());
        }
    }
}
