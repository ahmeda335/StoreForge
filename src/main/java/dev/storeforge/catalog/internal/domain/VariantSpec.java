package dev.storeforge.catalog.internal.domain;

import dev.storeforge.shared.money.Money;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The data needed to create or fully describe a variant. */
public record VariantSpec(@Nullable String sku, Money price, @Nullable Money compareAtPrice,
                          List<String> optionValues, @Nullable Integer weightGrams) {

    public VariantSpec {
        optionValues = List.copyOf(optionValues);
        sku = sku == null || sku.isBlank() ? null : sku.trim();
    }
}
