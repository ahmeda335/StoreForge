package dev.storeforge.catalog.internal.web;

import dev.storeforge.catalog.internal.application.CatalogViews.ProductSummaryView;
import dev.storeforge.catalog.internal.application.CatalogViews.ProductView;
import dev.storeforge.catalog.internal.application.ProductService;
import dev.storeforge.catalog.internal.persistence.ProductQueries.AdminFilter;
import dev.storeforge.developer.ApiClientPrincipal;
import dev.storeforge.developer.DeveloperApi;
import dev.storeforge.developer.RequiresScope;
import dev.storeforge.developer.Scope;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Developer API (2026-10): products. Contract types are separate from the admin API's on purpose: they're versioned. */
@RestController
@RequestMapping(DeveloperApi.STORE_PATH + "/products")
@RequiredArgsConstructor
class DeveloperProductsController {

    private final ProductService products;

    record Price(String amount, String currency) {
        static @Nullable Price of(@Nullable Money money) {
            return money == null ? null : new Price(money.toDecimalString(), money.currency().getCurrencyCode());
        }
    }

    record ProductSummary(UUID id, String handle, String title, String status, @Nullable String vendor, int variantCount, Price minPrice,
                          Price maxPrice, Instant createdAt, Instant updatedAt) {
        static ProductSummary of(ProductSummaryView v) {
            return new ProductSummary(v.id(), v.handle(), v.title(), v.status(), v.vendor(), v.variantCount(), Price.of(v.minPrice()),
                    Price.of(v.maxPrice()), v.createdAt(), v.updatedAt());
        }
    }

    record Variant(UUID id, String title, @Nullable String sku, Price price, @Nullable Price compareAtPrice, List<String> optionValues) {
    }

    record Product(UUID id, String handle, String title, String description, @Nullable String vendor, String status,
                   List<String> optionNames, List<Variant> variants, Instant createdAt, Instant updatedAt) {
        static Product of(ProductView v) {
            return new Product(v.id(), v.handle(), v.title(), v.description(), v.vendor(), v.status(), v.optionNames(),
                    v.variants().stream().map(x -> new Variant(x.id(), x.title(), x.sku(), Price.of(x.price()), Price.of(x.compareAtPrice()),
                            x.optionValues())).toList(),
                    v.createdAt(), v.updatedAt());
        }
    }

    @GetMapping
    @RequiresScope(Scope.READ_PRODUCTS)
    Page<ProductSummary> list(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion, @PathVariable UUID storeId,
                              @RequestParam(required = false) @Nullable Integer limit,
                              @RequestParam(required = false) @Nullable String cursor) {
        var page = products.list(client.actor(), client.storeId(), new AdminFilter(null, null, null, false), Page.limit(limit),
                cursor == null ? null : Cursor.decode(cursor));
        return new Page<>(page.data().stream().map(ProductSummary::of).toList(), page.page());
    }

    @GetMapping("/{productId}")
    @RequiresScope(Scope.READ_PRODUCTS)
    Product get(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion, @PathVariable UUID storeId,
                @PathVariable UUID productId) {
        return Product.of(products.get(client.actor(), client.storeId(), productId));
    }
}
