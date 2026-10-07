package dev.storeforge.catalog.internal.domain;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate root of the catalog. Invariants enforced here (and backed by constraints where the
 * database can express them):
 * <ul>
 *   <li>0–3 option names, unique; every variant has exactly one value per option;</li>
 *   <li>at least one and at most 100 variants; option combinations unique within the product;</li>
 *   <li>prices ≥ 0; compare-at price, if set, above the price.</li>
 * </ul>
 *
 * <p>Optimistic locking: {@code version} increases on EVERY change to the aggregate, including
 * variant changes (each variant operation also touches the root). Clients send the version they
 * edited back as {@code If-Match}; a mismatch means someone else changed the product meanwhile.
 */
@Entity
@Table(name = "products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    static final int MAX_VARIANTS = 100;

    @Id
    @Getter
    private UUID id;

    @Column(nullable = false)
    @Getter
    private UUID storeId;

    @Column(columnDefinition = "citext", nullable = false)
    @Getter
    private String handle;

    @Column(nullable = false)
    @Getter
    private String title;

    @Column(nullable = false)
    @Getter
    private String description;

    @Getter
    private String vendor;

    @Getter
    private String productType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Getter
    private ProductStatus status;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private String[] optionNames;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<Variant> variants = new ArrayList<>();

    @Column(nullable = false)
    @Getter
    private Instant createdAt;

    @Column(nullable = false)
    @Getter
    private Instant updatedAt;

    @Version
    private Long version;

    public static Product create(StoreId storeId, String handle, String title, @Nullable String description,
                                 @Nullable String vendor, @Nullable String productType, ProductStatus status,
                                 List<String> optionNames, List<VariantSpec> variants, Instant now) {
        Product product = new Product();
        product.id = Ids.newId();
        product.storeId = storeId.value();
        product.handle = ProductHandle.validate(handle);
        product.createdAt = now;
        product.status = status;
        product.setDetails(title, description, vendor, productType);
        product.optionNames = validateOptionNames(optionNames).toArray(String[]::new);
        if (variants.isEmpty()) {
            throw new CatalogExceptions.InvalidProduct("A product needs at least one variant.");
        }
        for (VariantSpec spec : variants) {
            product.addVariant(spec, now);
        }
        product.updatedAt = now;
        return product;
    }

    public void update(@Nullable String newHandle, @Nullable String newTitle, @Nullable String newDescription,
                       @Nullable String newVendor, @Nullable String newProductType, @Nullable ProductStatus newStatus, Instant now) {
        if (newHandle != null) {
            handle = ProductHandle.validate(newHandle);
        }
        setDetails(newTitle == null ? title : newTitle, newDescription == null ? description : newDescription,
                newVendor == null ? vendor : newVendor, newProductType == null ? productType : newProductType);
        if (newStatus != null) {
            status = newStatus;
        }
        updatedAt = now;
    }

    public Variant addVariant(VariantSpec spec, Instant now) {
        if (variants.size() >= MAX_VARIANTS) {
            throw new CatalogExceptions.InvalidProduct("A product can have at most " + MAX_VARIANTS + " variants.");
        }
        validateVariant(spec, null);
        int nextPosition = variants.stream().mapToInt(Variant::position).max().orElse(0) + 1;
        Variant variant = new Variant(this, spec, nextPosition, now);
        variants.add(variant);
        touch(now);
        return variant;
    }

    public Variant updateVariant(UUID variantId, VariantSpec spec, Instant now) {
        Variant variant = variant(variantId);
        validateVariant(spec, variant);
        variant.apply(spec, now);
        touch(now);
        return variant;
    }

    public void removeVariant(UUID variantId, Instant now) {
        Variant variant = variant(variantId);
        if (variants.size() == 1) {
            throw new CatalogExceptions.LastVariant();
        }
        variants.remove(variant);
        touch(now);
    }

    public Variant variant(UUID variantId) {
        return variants.stream().filter(v -> v.id().equals(variantId)).findFirst()
                .orElseThrow(CatalogExceptions.VariantNotFound::new);
    }

    /** Marks the root dirty so Hibernate increments the version even when only a variant changed. */
    private void touch(Instant now) {
        updatedAt = now;
    }

    private void setDetails(String newTitle, @Nullable String newDescription, @Nullable String newVendor, @Nullable String newProductType) {
        if (newTitle == null || newTitle.isBlank() || newTitle.length() > 255) {
            throw new CatalogExceptions.InvalidProduct("Title must be 1-255 characters.");
        }
        title = newTitle.trim();
        description = newDescription == null ? "" : newDescription;
        vendor = blankToNull(newVendor);
        productType = blankToNull(newProductType);
    }

    private void validateVariant(VariantSpec spec, @Nullable Variant replacing) {
        if (spec.price().isNegative()) {
            throw new CatalogExceptions.InvalidProduct("Price must not be negative.");
        }
        if (spec.compareAtPrice() != null && spec.compareAtPrice().amountMinor() <= spec.price().amountMinor()) {
            throw new CatalogExceptions.InvalidProduct("Compare-at price must be higher than the price.");
        }
        if (spec.optionValues().size() != optionNames.length) {
            throw new CatalogExceptions.InvalidProduct("Each variant needs exactly one value per option ("
                    + optionNames.length + ").");
        }
        if (spec.optionValues().stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 100)) {
            throw new CatalogExceptions.InvalidProduct("Option values must be 1-100 characters.");
        }
        boolean duplicate = variants.stream()
                .filter(existing -> existing != replacing)
                .anyMatch(existing -> existing.optionValues().equals(spec.optionValues()));
        if (duplicate) {
            throw new CatalogExceptions.DuplicateVariant();
        }
    }

    private static List<String> validateOptionNames(List<String> names) {
        if (names.size() > 3) {
            throw new CatalogExceptions.InvalidProduct("A product can have at most 3 options.");
        }
        Set<String> seen = new HashSet<>();
        List<String> trimmed = new ArrayList<>();
        for (String name : names) {
            String value = Objects.requireNonNullElse(name, "").trim();
            if (value.isEmpty() || value.length() > 50 || !seen.add(value.toLowerCase(java.util.Locale.ROOT))) {
                throw new CatalogExceptions.InvalidProduct("Option names must be unique and 1-50 characters.");
            }
            trimmed.add(value);
        }
        return trimmed;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public List<String> optionNames() {
        return List.of(optionNames);
    }

    public List<Variant> variants() {
        return List.copyOf(variants);
    }

    public long version() {
        return version == null ? 0 : version;
    }
}
