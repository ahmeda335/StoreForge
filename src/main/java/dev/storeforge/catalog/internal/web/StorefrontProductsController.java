package dev.storeforge.catalog.internal.web;

import dev.storeforge.catalog.internal.application.StorefrontCatalog;
import dev.storeforge.catalog.internal.web.CatalogDtos.StorefrontProductResponse;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.stores.CurrentStorefront;
import dev.storeforge.stores.StoreInfo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public storefront catalog. The store is resolved from the hostname ({@link CurrentStorefront}). */
@RestController
@RequestMapping("/storefront/v1/products")
@RequiredArgsConstructor
class StorefrontProductsController {

    private final StorefrontCatalog catalog;

    @GetMapping
    Page<StorefrontProductResponse> list(@CurrentStorefront StoreInfo store,
                                         @RequestParam(required = false) @Nullable Integer limit,
                                         @RequestParam(required = false) @Nullable String cursor) {
        var page = catalog.list(store, Page.limit(limit), cursor == null ? null : Cursor.decode(cursor));
        return new Page<>(page.data().stream().map(StorefrontProductResponse::of).toList(), page.page());
    }

    @GetMapping("/{handle}")
    StorefrontProductResponse get(@CurrentStorefront StoreInfo store, @PathVariable String handle) {
        return StorefrontProductResponse.of(catalog.get(store, handle));
    }
}
