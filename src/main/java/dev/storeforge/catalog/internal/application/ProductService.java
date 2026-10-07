package dev.storeforge.catalog.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.catalog.internal.application.CatalogViews.ProductSummaryView;
import dev.storeforge.catalog.internal.application.CatalogViews.ProductView;
import dev.storeforge.catalog.internal.application.ProductCommands.NewProduct;
import dev.storeforge.catalog.internal.application.ProductCommands.NewVariant;
import dev.storeforge.catalog.internal.application.ProductCommands.ProductChanges;
import dev.storeforge.catalog.internal.application.ProductCommands.VariantChanges;
import dev.storeforge.catalog.internal.domain.CatalogExceptions;
import dev.storeforge.catalog.internal.domain.Product;
import dev.storeforge.catalog.internal.domain.ProductHandle;
import dev.storeforge.catalog.internal.domain.ProductStatus;
import dev.storeforge.catalog.internal.domain.Variant;
import dev.storeforge.catalog.internal.domain.VariantSpec;
import dev.storeforge.catalog.internal.persistence.ProductQueries;
import dev.storeforge.catalog.internal.persistence.ProductQueries.AdminFilter;
import dev.storeforge.catalog.internal.persistence.ProductRepository;
import dev.storeforge.shared.error.ConstraintViolations;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Merchant-side catalog use cases. Every method: tenant transaction → permission check → load the
 * aggregate WITH the store id → change it through its methods → flush (so constraint violations
 * surface here and map to precise errors) → audit → return a view built inside the transaction.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository products;
    private final ProductQueries queries;
    private final StoreAccess access;
    private final StoreDirectory storeDirectory;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final Clock clock;

    public ProductView create(UUID userId, StoreId storeId, NewProduct command) {
        return translatingConstraints(() -> tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_WRITE);
            Currency currency = currency(storeId);
            Instant now = clock.instant();
            String handle = command.handle() == null ? ProductHandle.fromTitle(command.title()) : command.handle();
            Product product = Product.create(storeId, handle, command.title(), command.description(), command.vendor(),
                    command.productType(), command.status() == null ? ProductStatus.DRAFT : command.status(),
                    command.optionNames(), command.variants().stream().map(v -> spec(v, currency)).toList(), now);
            products.saveAndFlush(product);
            audit.recordUserAction(storeId, userId, "product.created", "product", product.id().toString(),
                    Map.of("handle", product.handle(), "title", product.title(), "variants", product.variants().size()));
            return ProductView.of(product, currency);
        }));
    }

    public Page<ProductSummaryView> list(UUID userId, StoreId storeId, AdminFilter filter, int limit, @Nullable Cursor after) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_READ);
            Currency currency = currency(storeId);
            var rows = queries.adminPage(storeId, filter, limit + 1, after);
            var page = Page.of(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
            return new Page<>(page.data().stream().map(row -> new ProductSummaryView(row.id(), row.handle(), row.title(),
                    row.status(), row.vendor(), row.variantCount(), Money.ofMinor(row.minPriceMinor(), currency),
                    Money.ofMinor(row.maxPriceMinor(), currency), row.createdAt(), row.updatedAt())).toList(), page.page());
        });
    }

    public ProductView get(UUID userId, StoreId storeId, UUID productId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_READ);
            return ProductView.of(load(storeId, productId), currency(storeId));
        });
    }

    public ProductView update(UUID userId, StoreId storeId, UUID productId, @Nullable Long expectedVersion, ProductChanges changes) {
        return translatingConstraints(() -> tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_WRITE);
            Product product = loadForUpdate(storeId, productId, expectedVersion);
            Map<String, Object> changed = changedFields(product, changes);
            product.update(changes.handle(), changes.title(), changes.description(), changes.vendor(),
                    changes.productType(), changes.status(), clock.instant());
            products.flush();
            audit.recordUserAction(storeId, userId, "product.updated", "product", productId.toString(), changed);
            return ProductView.of(product, currency(storeId));
        }));
    }

    public ProductView addVariant(UUID userId, StoreId storeId, UUID productId, @Nullable Long expectedVersion, NewVariant command) {
        return translatingConstraints(() -> tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_WRITE);
            Currency currency = currency(storeId);
            Product product = loadForUpdate(storeId, productId, expectedVersion);
            Variant variant = product.addVariant(spec(command, currency), clock.instant());
            products.flush();
            audit.recordUserAction(storeId, userId, "variant.created", "variant", variant.id().toString(),
                    Map.of("productId", productId.toString(), "price", variant.price(currency).toDecimalString()));
            return ProductView.of(product, currency);
        }));
    }

    public ProductView updateVariant(UUID userId, StoreId storeId, UUID productId, UUID variantId,
                                     @Nullable Long expectedVersion, VariantChanges changes) {
        return translatingConstraints(() -> tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_WRITE);
            Currency currency = currency(storeId);
            Product product = loadForUpdate(storeId, productId, expectedVersion);
            Variant current = product.variant(variantId);
            Money oldPrice = current.price(currency);
            VariantSpec merged = new VariantSpec(
                    changes.sku() == null ? current.sku() : changes.sku(),
                    changes.price() == null ? oldPrice : money(changes.price(), currency),
                    changes.compareAtPrice() == null ? current.compareAtPrice(currency) : money(changes.compareAtPrice(), currency),
                    changes.optionValues() == null ? current.optionValues() : changes.optionValues(),
                    changes.weightGrams() == null ? current.weightGrams() : changes.weightGrams());
            Variant variant = product.updateVariant(variantId, merged, clock.instant());
            products.flush();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("productId", productId.toString());
            if (!oldPrice.equals(variant.price(currency))) {
                details.put("priceFrom", oldPrice.toDecimalString());
                details.put("priceTo", variant.price(currency).toDecimalString());
            }
            audit.recordUserAction(storeId, userId, "variant.updated", "variant", variantId.toString(), details);
            return ProductView.of(product, currency);
        }));
    }

    public ProductView removeVariant(UUID userId, StoreId storeId, UUID productId, UUID variantId, @Nullable Long expectedVersion) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_WRITE);
            Product product = loadForUpdate(storeId, productId, expectedVersion);
            product.removeVariant(variantId, clock.instant());
            products.flush();
            audit.recordUserAction(storeId, userId, "variant.removed", "variant", variantId.toString(),
                    Map.of("productId", productId.toString()));
            return ProductView.of(product, currency(storeId));
        });
    }

    private Product load(StoreId storeId, UUID productId) {
        return products.findByStoreIdAndId(storeId.value(), productId).orElseThrow(CatalogExceptions.ProductNotFound::new);
    }

    /**
     * HTTP-level optimistic concurrency: the client says which version it edited (If-Match). If the
     * product changed since, we refuse with 412 instead of silently overwriting the other edit.
     * (Concurrent writers that both pass this check are still caught by the JPA @Version check at
     * flush time → 409.)
     */
    private Product loadForUpdate(StoreId storeId, UUID productId, @Nullable Long expectedVersion) {
        Product product = load(storeId, productId);
        if (expectedVersion != null && expectedVersion != product.version()) {
            throw new CatalogExceptions.PreconditionFailed();
        }
        return product;
    }

    private Currency currency(StoreId storeId) {
        return storeDirectory.find(storeId).orElseThrow(StoreNotFoundException::new).currency();
    }

    private static VariantSpec spec(NewVariant variant, Currency currency) {
        return new VariantSpec(variant.sku(), money(variant.price(), currency),
                variant.compareAtPrice() == null ? null : money(variant.compareAtPrice(), currency),
                variant.optionValues(), variant.weightGrams());
    }

    private static Money money(String decimal, Currency currency) {
        try {
            return Money.parse(decimal, currency);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new CatalogExceptions.InvalidProduct("Invalid amount '" + decimal + "' for " + currency.getCurrencyCode() + ".");
        }
    }

    private static Map<String, Object> changedFields(Product product, ProductChanges changes) {
        Map<String, Object> changed = new LinkedHashMap<>();
        if (changes.status() != null && changes.status() != product.status()) {
            changed.put("statusFrom", product.status().name());
            changed.put("statusTo", changes.status().name());
        }
        for (var field : Map.of("handle", changes.handle() != null, "title", changes.title() != null,
                "description", changes.description() != null, "vendor", changes.vendor() != null,
                "productType", changes.productType() != null).entrySet()) {
            if (field.getValue()) {
                changed.put(field.getKey(), "changed");
            }
        }
        return changed;
    }

    /** Unique constraints are the source of truth for uniqueness; their names map to precise API errors. */
    private static <T> T translatingConstraints(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isUniqueViolation(e, "uq_products_store_handle")) {
                throw new CatalogExceptions.HandleTaken();
            }
            if (ConstraintViolations.isUniqueViolation(e, "uq_product_variants_store_sku")) {
                throw new CatalogExceptions.SkuTaken();
            }
            if (ConstraintViolations.isUniqueViolation(e, "uq_product_variants_options")) {
                throw new CatalogExceptions.DuplicateVariant();
            }
            throw e;
        }
    }
}
