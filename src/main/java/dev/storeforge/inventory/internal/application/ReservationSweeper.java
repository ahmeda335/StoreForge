package dev.storeforge.inventory.internal.application;

import dev.storeforge.inventory.internal.persistence.InventoryStore;
import dev.storeforge.inventory.internal.persistence.InventoryStore.ReservationRow;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns stock held by abandoned checkouts. Two steps:
 * <ol>
 *   <li>Cross-tenant: WHICH stores have expired reservations? (ids only, via the SECURITY DEFINER
 *       function: ADR-011)</li>
 *   <li>Per store, in a normal tenant transaction (RLS applies): claim a batch with
 *       {@code FOR UPDATE SKIP LOCKED}, mark EXPIRED, give the quantity back, write the ledger.</li>
 * </ol>
 * Safe with several app instances running it at once (SKIP LOCKED + status condition), and safe to
 * run twice (already-expired rows are no longer ACTIVE).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReservationSweeper {

    private static final int MAX_STORES_PER_RUN = 100;
    private static final int BATCH = 500;

    private final InventoryStore store;
    private final TenantTransactions tenant;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${storeforge.inventory.sweeper.interval:30s}", initialDelayString = "${storeforge.inventory.sweeper.interval:30s}")
    public void sweep() {
        int expired = sweepOnce();
        if (expired > 0) {
            log.atInfo().addKeyValue("expiredReservations", expired).log("Expired abandoned inventory reservations");
        }
    }

    /** @return number of reservations expired in this run */
    public int sweepOnce() {
        int total = 0;
        for (UUID storeId : store.storesWithExpiredReservations(MAX_STORES_PER_RUN)) {
            total += tenant.inStore(StoreId.of(storeId), () -> expireStore(StoreId.of(storeId)));
        }
        meterRegistry.counter("storeforge.inventory.reservations.expired").increment(total);
        return total;
    }

    private int expireStore(StoreId storeId) {
        Instant now = clock.instant();
        List<ReservationRow> rows = store.expireBatch(storeId, now, BATCH);
        for (ReservationRow row : rows) {
            store.settle(storeId, row.inventoryItemId(), row.quantity(), false, now);
            store.ledger(storeId, row.inventoryItemId(), 0, -row.quantity(), "EXPIRED", "reservation", row.id().toString(),
                    null, null, now);
        }
        return rows.size();
    }
}
