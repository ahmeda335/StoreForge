package dev.storeforge.catalog.internal.application;

import dev.storeforge.catalog.internal.application.CatalogViews.StorefrontImageView;
import dev.storeforge.catalog.internal.application.CatalogViews.StorefrontProductView;
import dev.storeforge.catalog.internal.application.CatalogViews.StorefrontVariantView;
import dev.storeforge.catalog.internal.domain.CatalogExceptions;
import dev.storeforge.catalog.internal.persistence.ProductQueries;
import dev.storeforge.catalog.internal.persistence.ProductQueries.ProductRow;
import dev.storeforge.catalog.internal.persistence.ProductQueries.VariantRow;
import dev.storeforge.inventory.Inventory;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.StoreInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Public, read-only catalog for customers: only ACTIVE products, no internal fields (SKU, status,
 * audit data). The store comes from the request hostname, never from user input. Still runs in a
 * tenant transaction, so RLS applies to storefront reads as well.
 */
@Service
@RequiredArgsConstructor
public class StorefrontCatalog {

    private final ProductQueries queries;
    private final Inventory inventory;
    private final ProductImageService images;
    private final TenantTransactions tenant;

    public Page<StorefrontProductView> list(StoreInfo store, int limit, @Nullable Cursor after) {
        return tenant.inStore(store.id(), () -> {
            List<ProductRow> rows = queries.activePage(store.id(), limit + 1, after);
            Page<ProductRow> page = Page.of(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
            Map<UUID, List<VariantRow>> variants = queries.variantsOf(store.id(), page.data().stream().map(ProductRow::id).toList());
            Map<UUID, Integer> stock = inventory.availability(store.id(),
                    variants.values().stream().flatMap(List::stream).map(VariantRow::id).toList());
            var imagesOf = images.ready(store.id(), page.data().stream().map(ProductRow::id).toList());
            return new Page<>(page.data().stream()
                    .map(row -> view(row, variants.getOrDefault(row.id(), List.of()), stock, imagesOf.getOrDefault(row.id(), List.of()), store))
                    .toList(), page.page());
        });
    }

    public StorefrontProductView get(StoreInfo store, String handle) {
        return tenant.inStore(store.id(), () -> {
            ProductRow row = queries.activeByHandle(store.id(), handle);
            if (row == null) {
                throw new CatalogExceptions.ProductNotFound();
            }
            List<VariantRow> variants = queries.variantsOf(store.id(), List.of(row.id())).getOrDefault(row.id(), List.of());
            Map<UUID, Integer> stock = inventory.availability(store.id(), variants.stream().map(VariantRow::id).toList());
            return view(row, variants, stock, images.ready(store.id(), List.of(row.id())).getOrDefault(row.id(), List.of()), store);
        });
    }

    /** In stock = untracked (null in the map) or at least one available unit. Never-stocked variants are unavailable. */
    private static boolean inStock(Map<UUID, Integer> stock, UUID variantId) {
        return stock.containsKey(variantId) ? stock.get(variantId) == null || stock.get(variantId) > 0 : false;
    }

    private static StorefrontProductView view(ProductRow row, List<VariantRow> variants, Map<UUID, Integer> stock,
                                              List<ProductImageService.ImageView> images, StoreInfo store) {
        return new StorefrontProductView(row.id(), row.handle(), row.title(), row.description(), row.vendor(), row.productType(),
                row.optionNames(), variants.stream().map(variant -> new StorefrontVariantView(variant.id(),
                        variant.optionValues().isEmpty() ? "Default" : String.join(" / ", variant.optionValues()),
                        Money.ofMinor(variant.priceMinor(), store.currency()),
                        variant.compareAtPriceMinor() == null ? null : Money.ofMinor(variant.compareAtPriceMinor(), store.currency()),
                        variant.optionValues(), inStock(stock, variant.id()))).toList(),
                images.stream().map(image -> new StorefrontImageView(image.url(), image.alt())).toList());
    }
}
