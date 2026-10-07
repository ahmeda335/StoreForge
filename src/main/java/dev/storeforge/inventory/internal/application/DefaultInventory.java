package dev.storeforge.inventory.internal.application;

import dev.storeforge.inventory.InsufficientStockException;
import dev.storeforge.inventory.Inventory;
import dev.storeforge.inventory.ReservationLine;
import dev.storeforge.inventory.internal.persistence.InventoryStore;
import dev.storeforge.inventory.internal.persistence.InventoryStore.Item;
import dev.storeforge.inventory.internal.persistence.InventoryStore.ReservationRow;
import dev.storeforge.shared.error.ConstraintViolations;
import dev.storeforge.shared.tenancy.StoreId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class DefaultInventory implements Inventory {

    private final InventoryStore store;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observations;
    private final Clock clock;

    /**
     * Lines are merged per variant and processed in ascending variant order. Two checkouts reserving
     * {X, Y} and {Y, X} would otherwise each lock one row and wait for the other's: a deadlock
     * (PostgreSQL aborts one with 40P01). A global order makes that cycle impossible.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(StoreId storeId, UUID checkoutId, List<ReservationLine> lines, Duration ttl) {
        // One Observation = a timer metric (low-cardinality tags only) AND a span (may also carry
        // high-cardinality attributes such as ids, which never become metric labels).
        Observation.createNotStarted("storeforge.inventory.reserve", observations)
                .contextualName("inventory.reserve")
                .lowCardinalityKeyValue("lines", lines.size() == 1 ? "single" : "multiple")
                .highCardinalityKeyValue("storeforge.checkout_id", checkoutId.toString())
                .observe(() -> doReserve(storeId, checkoutId, lines, ttl));
    }

    private void doReserve(StoreId storeId, UUID checkoutId, List<ReservationLine> lines, Duration ttl) {
        Instant now = clock.instant();
        Map<UUID, Integer> quantities = new TreeMap<>(Comparator.naturalOrder());
        lines.forEach(line -> quantities.merge(line.variantId(), line.quantity(), Integer::sum));

        for (var line : quantities.entrySet()) {
            Item item = itemFor(storeId, line.getKey(), now);
            if (!item.tracked() || store.hasReservation(storeId, checkoutId, item.id())) {
                continue;   // untracked: always available; already reserved for this checkout: idempotent retry
            }
            if (!store.tryReserve(storeId, item.id(), line.getValue(), now)) {
                meterRegistry.counter("storeforge.inventory.reservations", "outcome", "insufficient").increment();
                int available = store.find(storeId, line.getKey()).map(Item::available).orElse(0);
                // Throwing rolls back the WHOLE transaction, including lines reserved before this one.
                throw new InsufficientStockException(line.getKey(), available, line.getValue());
            }
            store.insertReservation(storeId, checkoutId, item.id(), line.getValue(), now.plus(ttl), now);
            store.ledger(storeId, item.id(), 0, line.getValue(), "RESERVED", "checkout", checkoutId.toString(), null, null, now);
            meterRegistry.counter("storeforge.inventory.reservations", "outcome", "reserved").increment();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int commit(StoreId storeId, UUID checkoutId) {
        return settle(storeId, checkoutId, "COMMITTED", true, "SOLD");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int release(StoreId storeId, UUID checkoutId) {
        return settle(storeId, checkoutId, "RELEASED", false, "RELEASED");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, Integer> availability(StoreId storeId, Collection<UUID> variantIds) {
        return store.availability(storeId, variantIds);
    }

    private int settle(StoreId storeId, UUID checkoutId, String status, boolean sold, String reason) {
        Instant now = clock.instant();
        List<ReservationRow> rows = store.transitionCheckout(storeId, checkoutId, status, now);
        for (ReservationRow row : rows) {
            store.settle(storeId, row.inventoryItemId(), row.quantity(), sold, now);
            store.ledger(storeId, row.inventoryItemId(), sold ? -row.quantity() : 0, -row.quantity(), reason,
                    "checkout", checkoutId.toString(), null, null, now);
        }
        return rows.size();
    }

    private Item itemFor(StoreId storeId, UUID variantId, Instant now) {
        try {
            return store.getOrCreate(storeId, variantId, now);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isForeignKeyViolation(e, "fk_inventory_items_variant")) {
                throw new InventoryExceptions.VariantNotFound();
            }
            throw e;
        }
    }
}
