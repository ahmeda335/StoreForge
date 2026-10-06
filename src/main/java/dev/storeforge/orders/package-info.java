/**
 * Orders: the record of a sale, from placement (inside checkout completion) through payment,
 * fulfillment or cancellation.
 *
 * <p>Public API: {@link dev.storeforge.orders.Orders} (place an order inside the caller's
 * transaction, and the payment-window policy checkout must reserve stock for).
 */
@ApplicationModule(displayName = "Orders")
package dev.storeforge.orders;

import org.springframework.modulith.ApplicationModule;
