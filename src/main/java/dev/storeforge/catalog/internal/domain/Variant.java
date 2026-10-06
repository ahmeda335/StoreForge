package dev.storeforge.catalog.internal.domain;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A purchasable version of a product (e.g. "M / Red"). Part of the Product aggregate: created,
 * changed and removed only through {@link Product}, never on its own.
 */
@Entity
@Table(name = "product_variants")
public class Variant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID storeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    private String sku;

    @Column(nullable = false)
    private long priceMinor;

    private Long compareAtPriceMinor;

    private String option1;
    private String option2;
    private String option3;

    @Column(nullable = false)
    private int position;

    private Integer weightGrams;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Variant() {
        // for JPA
    }

    Variant(Product product, VariantSpec spec, int position, Instant now) {
        this.id = Ids.newId();
        this.storeId = product.storeId();
        this.product = product;
        this.position = position;
        this.createdAt = now;
        apply(spec, now);
    }

    void apply(VariantSpec spec, Instant now) {
        this.sku = spec.sku();
        this.priceMinor = spec.price().amountMinor();
        this.compareAtPriceMinor = spec.compareAtPrice() == null ? null : spec.compareAtPrice().amountMinor();
        List<String> values = spec.optionValues();
        this.option1 = values.size() > 0 ? values.get(0) : null;
        this.option2 = values.size() > 1 ? values.get(1) : null;
        this.option3 = values.size() > 2 ? values.get(2) : null;
        this.weightGrams = spec.weightGrams();
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public @Nullable String sku() {
        return sku;
    }

    public Money price(Currency currency) {
        return Money.ofMinor(priceMinor, currency);
    }

    public @Nullable Money compareAtPrice(Currency currency) {
        return compareAtPriceMinor == null ? null : Money.ofMinor(compareAtPriceMinor, currency);
    }

    public List<String> optionValues() {
        List<String> values = new ArrayList<>(3);
        for (String value : new String[] {option1, option2, option3}) {
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    public String title() {
        List<String> values = optionValues();
        return values.isEmpty() ? "Default" : String.join(" / ", values);
    }

    public int position() {
        return position;
    }

    public @Nullable Integer weightGrams() {
        return weightGrams;
    }
}
