package dev.storeforge.orders.internal.persistence;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Shipments and their items (tenant data, RLS). */
@Repository
@RequiredArgsConstructor
public class ShipmentRecords {

    private final JdbcClient jdbc;

    public record Shipment(UUID id, UUID orderId, String status, @Nullable String carrier, @Nullable String trackingNumber, Instant shippedAt,
                           @Nullable Instant deliveredAt, @Nullable Instant cancelledAt, Map<UUID, Integer> items) {
    }

    public UUID insert(StoreId storeId, UUID orderId, Map<UUID, Integer> items, @Nullable String carrier, @Nullable String tracking, UUID userId,
                       Instant now) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO shipments (id, store_id, order_id, status, carrier, tracking_number, created_by, shipped_at)
                VALUES (:id, :store, :order, 'SHIPPED', :carrier, :tracking, :user, :now)
                """)
                .param("id", id).param("store", storeId.value()).param("order", orderId).param("carrier", carrier).param("tracking", tracking)
                .param("user", userId).param("now", Timestamp.from(now)).update();
        items.forEach((variant, quantity) -> jdbc.sql("""
                INSERT INTO shipment_items (store_id, shipment_id, variant_id, quantity) VALUES (:store, :shipment, :variant, :quantity)
                """)
                .param("store", storeId.value()).param("shipment", id).param("variant", variant).param("quantity", quantity).update());
        return id;
    }

    /** Ordered quantity per variant. */
    public Map<UUID, Integer> ordered(StoreId storeId, UUID orderId) {
        Map<UUID, Integer> ordered = new LinkedHashMap<>();
        jdbc.sql("SELECT variant_id, quantity FROM order_items WHERE store_id = :store AND order_id = :order ORDER BY variant_id")
                .param("store", storeId.value()).param("order", orderId)
                .query(rs -> {
                    ordered.put(rs.getObject("variant_id", UUID.class), rs.getInt("quantity"));
                });
        return ordered;
    }

    /** Ordered minus shipped (shipments not cancelled), per variant. Read under the order's row lock. */
    public Map<UUID, Integer> remaining(StoreId storeId, UUID orderId) {
        Map<UUID, Integer> remaining = new LinkedHashMap<>(ordered(storeId, orderId));
        jdbc.sql("""
                SELECT si.variant_id, sum(si.quantity) AS shipped FROM shipment_items si
                  JOIN shipments s ON s.store_id = si.store_id AND s.id = si.shipment_id
                 WHERE s.store_id = :store AND s.order_id = :order AND s.status <> 'CANCELLED'
                 GROUP BY si.variant_id
                """)
                .param("store", storeId.value()).param("order", orderId)
                .query(rs -> {
                    remaining.merge(rs.getObject("variant_id", UUID.class), -rs.getInt("shipped"), Integer::sum);
                });
        return remaining;
    }

    public Optional<Shipment> find(StoreId storeId, UUID id) {
        return jdbc.sql("SELECT * FROM shipments WHERE store_id = :store AND id = :id").param("store", storeId.value()).param("id", id)
                .query((rs, n) -> map(storeId, rs)).optional();
    }

    public List<Shipment> forOrder(StoreId storeId, UUID orderId) {
        return jdbc.sql("SELECT * FROM shipments WHERE store_id = :store AND order_id = :order ORDER BY shipped_at, id")
                .param("store", storeId.value()).param("order", orderId).query((rs, n) -> map(storeId, rs)).list();
    }

    public void setStatus(StoreId storeId, UUID id, String status, Instant now) {
        jdbc.sql("""
                UPDATE shipments SET status = :status,
                       delivered_at = CASE WHEN :status = 'DELIVERED' THEN :now ELSE delivered_at END,
                       cancelled_at = CASE WHEN :status = 'CANCELLED' THEN :now ELSE cancelled_at END
                 WHERE store_id = :store AND id = :id
                """)
                .param("status", status).param("now", Timestamp.from(now)).param("store", storeId.value()).param("id", id).update();
    }

    private Shipment map(StoreId storeId, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Map<UUID, Integer> items = new HashMap<>();
        jdbc.sql("SELECT variant_id, quantity FROM shipment_items WHERE store_id = :store AND shipment_id = :id")
                .param("store", storeId.value()).param("id", id)
                .query(row -> {
                    items.put(row.getObject("variant_id", UUID.class), row.getInt("quantity"));
                });
        Timestamp delivered = rs.getTimestamp("delivered_at");
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        return new Shipment(id, rs.getObject("order_id", UUID.class), rs.getString("status"), rs.getString("carrier"),
                rs.getString("tracking_number"), rs.getTimestamp("shipped_at").toInstant(), delivered == null ? null : delivered.toInstant(),
                cancelled == null ? null : cancelled.toInstant(), items);
    }
}
