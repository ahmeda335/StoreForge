package dev.storeforge.catalog.internal.web;

import dev.storeforge.catalog.internal.persistence.ProductSearch;
import dev.storeforge.shared.error.InvalidParameterException;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.CurrentStorefront;
import dev.storeforge.stores.StoreInfo;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /storefront/v1/search?q=&vendor=&productType=&minPrice=&maxPrice=&sort=&limit=&offset=} */
@RestController
@RequiredArgsConstructor
class StorefrontSearchController {

    private static final Set<String> SORTS = Set.of("relevance", "price_asc", "price_desc", "newest");

    private final ProductSearch search;
    private final TenantTransactions tenant;

    record Price(String amount, String currency) {
    }

    record HitResponse(UUID id, String handle, String title, @Nullable String vendor, @Nullable String productType, Price minPrice, Price maxPrice) {
    }

    record Facets(List<ProductSearch.Facet> vendors, List<ProductSearch.Facet> productTypes) {
    }

    record Paging(int limit, int offset, long total) {
    }

    record SearchResponse(List<HitResponse> data, Facets facets, Paging page) {
    }

    @GetMapping("/storefront/v1/search")
    SearchResponse search(@CurrentStorefront StoreInfo store, @RequestParam(required = false) @Nullable String q,
                          @RequestParam(required = false) @Nullable String vendor,
                          @RequestParam(required = false) @Nullable String productType,
                          @RequestParam(required = false) @Nullable String minPrice,
                          @RequestParam(required = false) @Nullable String maxPrice,
                          @RequestParam(defaultValue = "relevance") String sort,
                          @RequestParam(defaultValue = "20") int limit,
                          @RequestParam(defaultValue = "0") int offset) {
        if (q != null && q.length() > 200) {
            throw new InvalidParameterException("q", "at most 200 characters");
        }
        if (!SORTS.contains(sort)) {
            throw new InvalidParameterException("sort", SORTS.toString());
        }
        if (limit < 1 || limit > 50 || offset < 0 || offset > 1000) {
            throw new InvalidParameterException("limit/offset", "limit 1..50, offset 0..1000");
        }
        var criteria = new ProductSearch.Criteria(q, blankToNull(vendor), blankToNull(productType), money(minPrice, store, "minPrice"),
                money(maxPrice, store, "maxPrice"), sort, limit, offset);
        var result = tenant.inStore(store.id(), () -> search.search(store.id(), criteria));
        String currency = store.currency().getCurrencyCode();
        return new SearchResponse(result.hits().stream().map(h -> new HitResponse(h.id(), h.handle(), h.title(), h.vendor(), h.productType(),
                        new Price(Money.ofMinor(h.minPriceMinor(), store.currency()).toDecimalString(), currency),
                        new Price(Money.ofMinor(h.maxPriceMinor(), store.currency()).toDecimalString(), currency))).toList(),
                new Facets(result.vendors(), result.productTypes()), new Paging(limit, offset, result.total()));
    }

    private static @Nullable Long money(@Nullable String value, StoreInfo store, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Money.parse(value, store.currency()).amountMinor();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidParameterException(name, "a decimal amount like 19.99");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
