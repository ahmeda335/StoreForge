/**
 * Shipping rates: per-store rates by destination country and weight band, with optional free
 * shipping above a subtotal; quoted for checkouts. Public API: {@link dev.storeforge.shipping.ShippingRates}.
 * (Shipments, i.e. fulfilment of orders, belong to the orders module.)
 */
@ApplicationModule(displayName = "Shipping")
package dev.storeforge.shipping;

import org.springframework.modulith.ApplicationModule;
