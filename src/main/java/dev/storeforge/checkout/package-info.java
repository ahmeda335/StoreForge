/**
 * Checkout: carts and checkout sessions on the public storefront, up to and including checkout
 * completion (stock reserved, prices frozen, customer recorded). Order placement joins the
 * completion transaction in P8.
 *
 * <p>No public API: it is an entry point that orchestrates catalog, inventory and customers.
 */
@ApplicationModule(displayName = "Checkout")
package dev.storeforge.checkout;

import org.springframework.modulith.ApplicationModule;
