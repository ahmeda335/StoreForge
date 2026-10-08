package dev.storeforge.shipping;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.List;
import java.util.UUID;

/** Used by checkout, inside its tenant transaction. */
public interface ShippingRates {

    /** True if the store ships with rates at all (stores without rates ship for free: shipping 0). */
    boolean configured(StoreId storeId);

    /**
     * The rates that apply to this destination and weight, cheapest first, each with its price for
     * this order ({@code 0} once the subtotal after discounts reaches the rate's free-shipping threshold).
     */
    List<Quote> quotes(StoreId storeId, String country, int weightGrams, long subtotalAfterDiscountMinor);

    record Quote(UUID rateId, String name, long priceMinor) {
    }
}
