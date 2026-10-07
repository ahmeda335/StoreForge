/**
 * Payments: collecting the money for an order through an external provider, refunds, provider
 * webhooks and reconciliation. Card data never reaches StoreForge: the browser sends it to the
 * provider with the payment's client secret.
 *
 * <p>Public API: {@link dev.storeforge.payments.Payments} (start or look up the payment of an order,
 * used by the storefront checkout).
 */
@ApplicationModule(displayName = "Payments")
package dev.storeforge.payments;

import org.springframework.modulith.ApplicationModule;
