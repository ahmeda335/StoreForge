package dev.storeforge.inventory.internal.persistence;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All inventory SQL. The SQL IS the concurrency design here, which is why it is plain JDBC and not
 * hidden behind an ORM: read the WHERE clauses of {@link #tryReserve} and {@link #adjustOnHand}.
 */
@Repository
public class InventoryStore {

    private final JdbcClient jdbc;

    InventoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Item(UUID id, UUID variantId, int onHand, int reserved, boolean tracked, Instant updatedAt) {
        public int available() {
            return onHand - reserved;
        }
    }

    public record ReservationRow(UUID id, UUID inventoryItemId, int quantity) {
    }

    public record LedgerRow(UUID id, int deltaOnHand, int deltaReserved, String reason, @Nullable String referenceType,
                            @Nullable String referenceId, @Nullable UUID actorId, @Nullable String note, Instant createdAt) {
    }

    /**
     * Creates the item lazily on first use. ON CONFLICT DO NOTHING makes concurrent first uses safe:
     * exactly one INSERT wins, the others become no-ops. A variant id from another store (or one
     * that doesn't exist) fails the composite foreign key.
     */
    public Item getOrCreate(StoreId storeId, UUID variantId, Instant now) {
        jdbc.sql("""
                INSERT INTO inventory_items (id, store_id, variant_id, created_at, updated_at)
                VALUES (:id, :storeId, :variantId, :now, :now)
                ON CONFLICT (store_id, variant_id) DO NOTHING
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("variantId", variantId)
                .param("now", Timestamp.from(now))
                .update();
        return find(storeId, variantId).orElseThrow();
    }

    public Optional<Item> find(StoreId storeId, UUID variantId) {
        return jdbc.sql("""
                SELECT id, variant_id, on_hand, reserved, tracked, updated_at FROM inventory_items
                 WHERE store_id = :storeId AND variant_id = :variantId
                """)
                .param("storeId", storeId.value())
                .param("variantId", variantId)
                .query((rs, rowNum) -> new Item(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                        rs.getInt("on_hand"), rs.getInt("reserved"), rs.getBoolean("tracked"), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /**
     * THE oversell guard (ADR-004). Check and write are ONE statement: PostgreSQL row-locks the item,
     * and if another transaction changed it meanwhile, re-evaluates the WHERE clause against the new
     * committed version (READ COMMITTED "EvalPlanQual"). There is no window between "read available"
     * and "write reserved". 1 row = reserved; 0 rows = not enough stock.
     */
    public boolean tryReserve(StoreId storeId, UUID itemId, int quantity, Instant now) {
        return jdbc.sql("""
                UPDATE inventory_items SET reserved = reserved + :quantity, updated_at = :now
                 WHERE store_id = :storeId AND id = :itemId AND on_hand - reserved >= :quantity
                """)
                .param("storeId", storeId.value())
                .param("itemId", itemId)
                .param("quantity", quantity)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /** Adjusts physical stock; refuses to go below what is already promised to customers (reserved). */
    public boolean adjustOnHand(StoreId storeId, UUID itemId, int delta, Instant now) {
        return jdbc.sql("""
                UPDATE inventory_items SET on_hand = on_hand + :delta, updated_at = :now
                 WHERE store_id = :storeId AND id = :itemId AND on_hand + :delta >= reserved
                """)
                .param("storeId", storeId.value())
                .param("itemId", itemId)
                .param("delta", delta)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    public void setTracked(StoreId storeId, UUID itemId, boolean tracked, Instant now) {
        jdbc.sql("UPDATE inventory_items SET tracked = :tracked, updated_at = :now WHERE store_id = :storeId AND id = :itemId")
                .param("storeId", storeId.value())
                .param("itemId", itemId)
                .param("tracked", tracked)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Moves reserved stock out: either sold (on_hand and reserved both drop) or released (reserved drops). */
    public void settle(StoreId storeId, UUID itemId, int quantity, boolean sold, Instant now) {
        jdbc.sql("""
                UPDATE inventory_items
                   SET reserved = reserved - :quantity,
                       on_hand = on_hand - CASE WHEN :sold THEN :quantity ELSE 0 END,
                       updated_at = :now
                 WHERE store_id = :storeId AND id = :itemId
                """)
                .param("storeId", storeId.value())
                .param("itemId", itemId)
                .param("quantity", quantity)
                .param("sold", sold)
                .param("now", Timestamp.from(now))
                .update();
    }

    public boolean hasReservation(StoreId storeId, UUID checkoutId, UUID itemId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM inventory_reservations
                                WHERE store_id = :storeId AND checkout_id = :checkoutId AND inventory_item_id = :itemId)
                """)
                .param("storeId", storeId.value())
                .param("checkoutId", checkoutId)
                .param("itemId", itemId)
                .query(Boolean.class)
                .single();
    }

    public void insertReservation(StoreId storeId, UUID checkoutId, UUID itemId, int quantity, Instant expiresAt, Instant now) {
        jdbc.sql("""
                INSERT INTO inventory_reservations (id, store_id, inventory_item_id, checkout_id, quantity, status,
                                                    expires_at, created_at, updated_at)
                VALUES (:id, :storeId, :itemId, :checkoutId, :quantity, 'ACTIVE', :expiresAt, :now, :now)
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("itemId", itemId)
                .param("checkoutId", checkoutId)
                .param("quantity", quantity)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * ACTIVE → newStatus for one checkout, returning what was transitioned. The status condition
     * makes it idempotent and race-safe: of two concurrent callers, only one gets rows back.
     * Rows are returned sorted by item id so callers lock items in a deterministic order.
     */
    public List<ReservationRow> transitionCheckout(StoreId storeId, UUID checkoutId, String newStatus, Instant now) {
        return jdbc.sql("""
                UPDATE inventory_reservations SET status = :newStatus, updated_at = :now
                 WHERE store_id = :storeId AND checkout_id = :checkoutId AND status = 'ACTIVE'
                RETURNING id, inventory_item_id, quantity
                """)
                .param("storeId", storeId.value())
                .param("checkoutId", checkoutId)
                .param("newStatus", newStatus)
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> new ReservationRow(rs.getObject("id", UUID.class),
                        rs.getObject("inventory_item_id", UUID.class), rs.getInt("quantity")))
                .list()
                .stream().sorted(Comparator.comparing(ReservationRow::inventoryItemId)).toList();
    }

    /**
     * Claims up to {@code batch} expired ACTIVE reservations of one store. FOR UPDATE SKIP LOCKED:
     * two sweepers (two app instances) never claim the same rows and never wait for each other, and
     * a sweeper doesn't block on a reservation a checkout is committing right now.
     */
    public List<ReservationRow> expireBatch(StoreId storeId, Instant now, int batch) {
        return jdbc.sql("""
                WITH expired AS (
                    SELECT id FROM inventory_reservations
                     WHERE store_id = :storeId AND status = 'ACTIVE' AND expires_at < :now
                     ORDER BY expires_at
                     LIMIT :batch
                       FOR UPDATE SKIP LOCKED)
                UPDATE inventory_reservations r SET status = 'EXPIRED', updated_at = :now
                  FROM expired WHERE r.id = expired.id
                RETURNING r.id, r.inventory_item_id, r.quantity
                """)
                .param("storeId", storeId.value())
                .param("now", Timestamp.from(now))
                .param("batch", batch)
                .query((rs, rowNum) -> new ReservationRow(rs.getObject("id", UUID.class),
                        rs.getObject("inventory_item_id", UUID.class), rs.getInt("quantity")))
                .list()
                .stream().sorted(Comparator.comparing(ReservationRow::inventoryItemId)).toList();
    }

    /** Cross-tenant: only store ids, via the SECURITY DEFINER function (ADR-011). */
    public List<UUID> storesWithExpiredReservations(int maxStores) {
        return jdbc.sql("SELECT store_id FROM stores_with_expired_reservations(:max)")
                .param("max", maxStores)
                .query(UUID.class)
                .list();
    }

    public void ledger(StoreId storeId, UUID itemId, int deltaOnHand, int deltaReserved, String reason,
                       @Nullable String referenceType, @Nullable String referenceId, @Nullable UUID actorId,
                       @Nullable String note, Instant now) {
        jdbc.sql("""
                INSERT INTO inventory_transactions (id, store_id, inventory_item_id, delta_on_hand, delta_reserved, reason,
                                                    reference_type, reference_id, actor_id, note, created_at)
                VALUES (:id, :storeId, :itemId, :deltaOnHand, :deltaReserved, :reason, :referenceType, :referenceId,
                        :actorId, :note, :now)
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("itemId", itemId)
                .param("deltaOnHand", deltaOnHand)
                .param("deltaReserved", deltaReserved)
                .param("reason", reason)
                .param("referenceType", referenceType)
                .param("referenceId", referenceId)
                .param("actorId", actorId)
                .param("note", note)
                .param("now", Timestamp.from(now))
                .update();
    }

    public List<LedgerRow> ledgerPage(StoreId storeId, UUID itemId, int limitPlusOne, @Nullable Instant afterTime, @Nullable UUID afterId) {
        String keyset = afterTime == null ? "" : "AND (created_at, id) < (:afterTime, :afterId)";
        var statement = jdbc.sql("""
                SELECT id, delta_on_hand, delta_reserved, reason, reference_type, reference_id, actor_id, note, created_at
                  FROM inventory_transactions
                 WHERE store_id = :storeId AND inventory_item_id = :itemId %s
                 ORDER BY created_at DESC, id DESC LIMIT :limit
                """.formatted(keyset))
                .param("storeId", storeId.value())
                .param("itemId", itemId)
                .param("limit", limitPlusOne);
        if (afterTime != null) {
            statement = statement.param("afterTime", Timestamp.from(afterTime)).param("afterId", afterId);
        }
        return statement.query((rs, rowNum) -> new LedgerRow(rs.getObject("id", UUID.class), rs.getInt("delta_on_hand"),
                        rs.getInt("delta_reserved"), rs.getString("reason"), rs.getString("reference_type"),
                        rs.getString("reference_id"), rs.getObject("actor_id", UUID.class), rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** SUM of ledger deltas per item: must equal the current levels (reconciliation). */
    public Map<UUID, int[]> ledgerTotals(StoreId storeId, Collection<UUID> itemIds) {
        Map<UUID, int[]> totals = new HashMap<>();
        jdbc.sql("""
                SELECT inventory_item_id, sum(delta_on_hand) AS on_hand, sum(delta_reserved) AS reserved
                  FROM inventory_transactions WHERE store_id = :storeId AND inventory_item_id = ANY(:ids)
                 GROUP BY inventory_item_id
                """)
                .param("storeId", storeId.value())
                .param("ids", itemIds.toArray(UUID[]::new))
                .query(rs -> {
                    totals.put(rs.getObject("inventory_item_id", UUID.class), new int[] {rs.getInt("on_hand"), rs.getInt("reserved")});
                });
        return totals;
    }

    /** Available per variant for a set of variants; null value = untracked. */
    public Map<UUID, Integer> availability(StoreId storeId, Collection<UUID> variantIds) {
        Map<UUID, Integer> result = new HashMap<>();
        if (variantIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                SELECT variant_id, tracked, on_hand - reserved AS available FROM inventory_items
                 WHERE store_id = :storeId AND variant_id = ANY(:variantIds)
                """)
                .param("storeId", storeId.value())
                .param("variantIds", variantIds.toArray(UUID[]::new))
                .query(rs -> {
                    result.put(rs.getObject("variant_id", UUID.class), rs.getBoolean("tracked") ? rs.getInt("available") : null);
                });
        return result;
    }
}
