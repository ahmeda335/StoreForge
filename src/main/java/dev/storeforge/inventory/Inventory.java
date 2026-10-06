package dev.storeforge.inventory;

import dev.storeforge.shared.tenancy.StoreId;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stock operations for other modules. Every method must run inside the caller's tenant
 * transaction ({@code TenantTransactions.inStore}).
 *
 * <pre>
 *   reserve ──► ACTIVE ──commit──► COMMITTED   (sold: on_hand -= q, reserved -= q)
 *                  │ ──release──► RELEASED     (cancelled: reserved -= q)
 *                  └──(expiry)──► EXPIRED      (abandoned: reserved -= q, by the sweeper)
 * </pre>
 */
public interface Inventory {

    /**
     * Reserves all lines or none ({@link InsufficientStockException}). Idempotent per checkout: lines
     * already reserved for this checkout are not reserved again, so a retried request is safe.
     */
    void reserve(StoreId storeId, UUID checkoutId, List<ReservationLine> lines, Duration ttl);

    /** Turns this checkout's ACTIVE reservations into sales. Idempotent: returns the number of reservations committed now. */
    int commit(StoreId storeId, UUID checkoutId);

    /** Gives this checkout's ACTIVE reservations back. Idempotent. */
    int release(StoreId storeId, UUID checkoutId);

    /** Available quantity per variant; {@code null} value = untracked (always available). Missing variants count as 0. */
    Map<UUID, Integer> availability(StoreId storeId, Collection<UUID> variantIds);
}
