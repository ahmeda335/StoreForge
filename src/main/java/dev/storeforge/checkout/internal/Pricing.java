package dev.storeforge.checkout.internal;

import dev.storeforge.catalog.SellableVariant;
import dev.storeforge.catalog.VariantLookup;
import dev.storeforge.checkout.internal.CartStore.Item;
import dev.storeforge.checkout.internal.CheckoutStore.Line;
import dev.storeforge.shared.tenancy.StoreId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Prices cart items from the CATALOG, never from client input. Every amount the customer pays is
 * computed here, server-side, from current prices (blueprint 18: "never trust the client").
 *
 * <p>Shipping and tax are 0 until discounts/shipping arrive (P13); the totals already carry them so
 * the API and the frozen snapshot don't change shape later.
 */
@Component
@RequiredArgsConstructor
class Pricing {

    private final VariantLookup variants;

    record PricedCart(List<Line> lines, List<Line> unavailable, long subtotal, long shipping, long tax) {
        long total() {
            return Math.addExact(Math.addExact(subtotal, shipping), tax);
        }
    }

    /** Must run in the caller's tenant transaction. Unknown or non-sellable variants end up in {@code unavailable}. */
    PricedCart price(StoreId storeId, List<Item> items) {
        Map<java.util.UUID, SellableVariant> catalog = variants.find(storeId, items.stream().map(Item::variantId).toList());
        List<Line> lines = new ArrayList<>();
        List<Line> unavailable = new ArrayList<>();
        long subtotal = 0;
        for (Item item : items) {
            SellableVariant variant = catalog.get(item.variantId());
            if (variant == null || !variant.sellable()) {
                unavailable.add(new Line(item.variantId(), variant == null ? "Unavailable item" : variant.productTitle(),
                        variant == null ? "" : variant.variantTitle(), null, 0, item.quantity(), 0));
                continue;
            }
            long lineTotal = Math.multiplyExact(variant.priceMinor(), item.quantity());
            lines.add(new Line(variant.variantId(), variant.productTitle(), variant.variantTitle(), variant.sku(),
                    variant.priceMinor(), item.quantity(), lineTotal));
            subtotal = Math.addExact(subtotal, lineTotal);
        }
        return new PricedCart(lines, unavailable, subtotal, 0, 0);
    }
}
