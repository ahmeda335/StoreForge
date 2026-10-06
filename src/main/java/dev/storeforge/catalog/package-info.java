/**
 * Catalog: products, their options and variants (prices, SKUs), for the merchant admin API and the
 * public storefront API.
 *
 * <p>Public API: {@link dev.storeforge.catalog.VariantLookup} (current price and sellability of
 * variants, used by checkout).
 */
@ApplicationModule(displayName = "Catalog")
package dev.storeforge.catalog;

import org.springframework.modulith.ApplicationModule;
