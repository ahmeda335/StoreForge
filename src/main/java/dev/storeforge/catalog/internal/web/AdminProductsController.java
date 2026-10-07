package dev.storeforge.catalog.internal.web;

import dev.storeforge.catalog.internal.application.CatalogViews.ProductView;
import dev.storeforge.catalog.internal.application.ProductCommands.NewProduct;
import dev.storeforge.catalog.internal.application.ProductCommands.NewVariant;
import dev.storeforge.catalog.internal.application.ProductCommands.ProductChanges;
import dev.storeforge.catalog.internal.application.ProductCommands.VariantChanges;
import dev.storeforge.catalog.internal.application.ProductService;
import dev.storeforge.catalog.internal.domain.CatalogExceptions;
import dev.storeforge.catalog.internal.domain.ProductStatus;
import dev.storeforge.catalog.internal.persistence.ProductQueries.AdminFilter;
import dev.storeforge.catalog.internal.web.CatalogDtos.CreateProductRequest;
import dev.storeforge.catalog.internal.web.CatalogDtos.ProductResponse;
import dev.storeforge.catalog.internal.web.CatalogDtos.ProductSummaryResponse;
import dev.storeforge.catalog.internal.web.CatalogDtos.UpdateProductRequest;
import dev.storeforge.catalog.internal.web.CatalogDtos.VariantPatchRequest;
import dev.storeforge.catalog.internal.web.CatalogDtos.VariantRequest;
import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.error.InvalidParameterException;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant catalog API. Every single-product response carries an {@code ETag} with the product's
 * version; sending it back as {@code If-Match} on writes turns "last write wins" into "conflicting
 * write is rejected (412)".
 */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/products")
@RequiredArgsConstructor
class AdminProductsController {

    private final ProductService products;

    @PostMapping
    ResponseEntity<ProductResponse> create(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                           @Valid @RequestBody CreateProductRequest body) {
        ProductView view = products.create(user.userId(), StoreId.of(storeId), new NewProduct(body.handle(), body.title(),
                body.description(), body.vendor(), body.productType(), body.status(), body.options(),
                body.variants().stream().map(AdminProductsController::newVariant).toList()));
        return ResponseEntity.created(URI.create("/admin/v1/stores/" + storeId + "/products/" + view.id()))
                .eTag(etag(view)).body(ProductResponse.of(view));
    }

    @GetMapping
    Page<ProductSummaryResponse> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                      @RequestParam(required = false) @Nullable String status,
                                      @RequestParam(required = false) @Nullable String vendor,
                                      @RequestParam(required = false) @Nullable String q,
                                      @RequestParam(defaultValue = "-createdAt") String sort,
                                      @RequestParam(required = false) @Nullable Integer limit,
                                      @RequestParam(required = false) @Nullable String cursor) {
        // Allow-lists, never pass-through: only known values reach SQL.
        if (!sort.equals("-createdAt") && !sort.equals("createdAt")) {
            throw new InvalidParameterException("sort", "createdAt, -createdAt");
        }
        String statusFilter = status == null ? null : parseStatus(status).name();
        var filter = new AdminFilter(statusFilter, blankToNull(vendor), blankToNull(q), sort.equals("createdAt"));
        var page = products.list(user.userId(), StoreId.of(storeId), filter, Page.limit(limit),
                cursor == null ? null : Cursor.decode(cursor));
        return new Page<>(page.data().stream().map(ProductSummaryResponse::of).toList(), page.page());
    }

    @GetMapping("/{productId}")
    ResponseEntity<ProductResponse> get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                        @PathVariable UUID productId) {
        ProductView view = products.get(user.userId(), StoreId.of(storeId), productId);
        return ResponseEntity.ok().eTag(etag(view)).body(ProductResponse.of(view));
    }

    @PatchMapping("/{productId}")
    ResponseEntity<ProductResponse> update(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                           @PathVariable UUID productId,
                                           @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
                                           @Valid @RequestBody UpdateProductRequest body) {
        ProductView view = products.update(user.userId(), StoreId.of(storeId), productId, expectedVersion(ifMatch),
                new ProductChanges(body.handle(), body.title(), body.description(), body.vendor(), body.productType(), body.status()));
        return ResponseEntity.ok().eTag(etag(view)).body(ProductResponse.of(view));
    }

    @PostMapping("/{productId}/variants")
    ResponseEntity<ProductResponse> addVariant(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                               @PathVariable UUID productId,
                                               @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
                                               @Valid @RequestBody VariantRequest body) {
        ProductView view = products.addVariant(user.userId(), StoreId.of(storeId), productId, expectedVersion(ifMatch), newVariant(body));
        return ResponseEntity.status(201).eTag(etag(view)).body(ProductResponse.of(view));
    }

    @PatchMapping("/{productId}/variants/{variantId}")
    ResponseEntity<ProductResponse> updateVariant(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                                  @PathVariable UUID productId, @PathVariable UUID variantId,
                                                  @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
                                                  @Valid @RequestBody VariantPatchRequest body) {
        ProductView view = products.updateVariant(user.userId(), StoreId.of(storeId), productId, variantId, expectedVersion(ifMatch),
                new VariantChanges(body.sku(), body.price(), body.compareAtPrice(), body.options(), body.weightGrams()));
        return ResponseEntity.ok().eTag(etag(view)).body(ProductResponse.of(view));
    }

    @DeleteMapping("/{productId}/variants/{variantId}")
    ResponseEntity<ProductResponse> removeVariant(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                                  @PathVariable UUID productId, @PathVariable UUID variantId,
                                                  @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch) {
        ProductView view = products.removeVariant(user.userId(), StoreId.of(storeId), productId, variantId, expectedVersion(ifMatch));
        return ResponseEntity.ok().eTag(etag(view)).body(ProductResponse.of(view));
    }

    private static NewVariant newVariant(VariantRequest body) {
        return new NewVariant(body.sku(), body.price(), body.compareAtPrice(), body.options(), body.weightGrams());
    }

    private static String etag(ProductView view) {
        return "\"v" + view.version() + "\"";
    }

    /** {@code If-Match: "v7"} → 7. Anything we never issued can't match → 412. Absent → no precondition. */
    private static @Nullable Long expectedVersion(@Nullable String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.startsWith("\"v") && value.endsWith("\"")) {
            try {
                return Long.parseLong(value.substring(2, value.length() - 1));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new CatalogExceptions.PreconditionFailed();
    }

    private static ProductStatus parseStatus(String status) {
        try {
            return ProductStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("status",
                    Arrays.stream(ProductStatus.values()).map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
