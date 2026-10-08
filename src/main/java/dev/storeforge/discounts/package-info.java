/**
 * Discount codes: rules (percentage / fixed amount, minimum subtotal, validity window, usage limit,
 * once per customer), evaluation for open checkouts, and redemption at checkout completion with a
 * race-free usage limit. Public API: {@link dev.storeforge.discounts.Discounts}.
 */
@ApplicationModule(displayName = "Discounts")
package dev.storeforge.discounts;

import org.springframework.modulith.ApplicationModule;
