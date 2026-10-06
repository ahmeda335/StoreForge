/**
 * Inventory: stock levels per variant, reservations and the stock ledger.
 *
 * <p>Public API: {@link dev.storeforge.inventory.Inventory} (reserve / commit / release /
 * availability), used by checkout and orders. All of it runs inside the CALLER's tenant
 * transaction, so a reservation commits or rolls back together with the order that needs it.
 *
 * <p>Concurrency design: ADR-004 (single-statement conditional updates + CHECK constraints,
 * deterministic lock order).
 */
@ApplicationModule(displayName = "Inventory")
package dev.storeforge.inventory;

import org.springframework.modulith.ApplicationModule;
