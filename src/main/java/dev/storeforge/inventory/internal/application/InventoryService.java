package dev.storeforge.inventory.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.inventory.internal.persistence.InventoryStore;
import dev.storeforge.inventory.internal.persistence.InventoryStore.Item;
import dev.storeforge.inventory.internal.persistence.InventoryStore.LedgerRow;
import dev.storeforge.shared.error.ConstraintViolations;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Merchant-facing stock management: levels, manual adjustments, ledger. */
@Service
@RequiredArgsConstructor
public class InventoryService {

    static final Set<String> ADJUSTMENT_REASONS = Set.of("RECEIVED", "CORRECTION", "DAMAGED", "RETURNED");

    private final InventoryStore store;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final InventoryEventPublisher events;
    private final Clock clock;

    public record Level(UUID itemId, UUID variantId, int onHand, int reserved, int available, boolean tracked) {
        static Level of(Item item) {
            return new Level(item.id(), item.variantId(), item.onHand(), item.reserved(), item.available(), item.tracked());
        }
    }

    public Level level(UUID userId, StoreId storeId, UUID variantId) {
        return variantScoped(() -> tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.INVENTORY_READ);
            return Level.of(store.getOrCreate(storeId, variantId, clock.instant()));
        }));
    }

    /**
     * Manual stock change. The conditional UPDATE refuses to push on-hand below the reserved amount:
     * those units are promised to customers in checkout. The ledger row and audit entry commit in
     * the same transaction as the level change.
     */
    public Level adjust(UUID userId, StoreId storeId, UUID variantId, int delta, String reason, @Nullable String note) {
        if (delta == 0) {
            throw new InventoryExceptions.InvalidAdjustment("delta must not be 0.");
        }
        if (!ADJUSTMENT_REASONS.contains(reason)) {
            throw new InventoryExceptions.InvalidAdjustment("reason must be one of " + ADJUSTMENT_REASONS + ".");
        }
        return variantScoped(() -> tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.INVENTORY_WRITE);
            Instant now = clock.instant();
            Item item = store.getOrCreate(storeId, variantId, now);
            if (!store.adjustOnHand(storeId, item.id(), delta, now)) {
                Item current = store.find(storeId, variantId).orElseThrow();
                throw new InventoryExceptions.BelowReserved(current.onHand(), current.reserved(), delta);
            }
            store.ledger(storeId, item.id(), delta, 0, reason, "adjustment", null, userId, note, now);
            Item updated = store.find(storeId, variantId).orElseThrow();
            events.levelChanged(storeId, updated, delta, reason);
            audit.recordUserAction(storeId, userId, "inventory.adjusted", "variant", variantId.toString(),
                    Map.of("delta", delta, "reason", reason, "onHand", updated.onHand()));
            return Level.of(updated);
        }));
    }

    public Level setTracked(UUID userId, StoreId storeId, UUID variantId, boolean tracked) {
        return variantScoped(() -> tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.INVENTORY_WRITE);
            Instant now = clock.instant();
            Item item = store.getOrCreate(storeId, variantId, now);
            store.setTracked(storeId, item.id(), tracked, now);
            audit.recordUserAction(storeId, userId, "inventory.tracking_changed", "variant", variantId.toString(),
                    Map.of("tracked", tracked));
            return Level.of(store.find(storeId, variantId).orElseThrow());
        }));
    }

    public Page<LedgerRow> ledger(UUID userId, StoreId storeId, UUID variantId, int limit, @Nullable Cursor after) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.INVENTORY_READ);
            Item item = store.find(storeId, variantId).orElse(null);
            if (item == null) {
                return new Page<>(java.util.List.of(), new Page.PageInfo(null, false));
            }
            var rows = store.ledgerPage(storeId, item.id(), limit + 1, after == null ? null : after.timestamp(),
                    after == null ? null : after.id());
            return Page.of(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
        });
    }

    /** A variant id that doesn't exist in THIS store fails the composite foreign key → 404. */
    private static <T> T variantScoped(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isForeignKeyViolation(e, "fk_inventory_items_variant")) {
                throw new InventoryExceptions.VariantNotFound();
            }
            throw e;
        }
    }
}
