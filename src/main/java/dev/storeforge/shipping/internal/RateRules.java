package dev.storeforge.shipping.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Pure rate applicability and pricing. */
final class RateRules {

    private RateRules() {
    }

    record Rate(List<String> countries, int minWeightGrams, @Nullable Integer maxWeightGrams, long priceMinor, @Nullable Long freeOverMinor,
                boolean active) {
    }

    /** Weight bands are half-open [min, max): a 1000 g parcel belongs to "1000-2000", not to "0-1000". */
    static boolean applies(Rate rate, String country, int weightGrams) {
        return rate.active()
                && (rate.countries().isEmpty() || rate.countries().contains(country))
                && weightGrams >= rate.minWeightGrams()
                && (rate.maxWeightGrams() == null || weightGrams < rate.maxWeightGrams());
    }

    /** Free shipping is measured on what the customer pays for the goods: the subtotal AFTER discounts. */
    static long price(Rate rate, long subtotalAfterDiscountMinor) {
        return rate.freeOverMinor() != null && subtotalAfterDiscountMinor >= rate.freeOverMinor() ? 0 : rate.priceMinor();
    }
}
