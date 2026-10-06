package dev.storeforge.stores;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.Currency;

/** Public, non-sensitive facts about a store that other modules need (currency for prices, status for storefronts). */
public record StoreInfo(StoreId id, String handle, String name, Currency currency, boolean active) {
}
