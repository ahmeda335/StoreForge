package dev.storeforge.orders.internal.persistence;

import dev.storeforge.orders.Orders.PlacedLine;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Order numbering, immutable line items and the order timeline (plain SQL; all append-only or counters). */
@Repository
@RequiredArgsConstructor
public class OrderRecords {

    private static final long FIRST_ORDER_NUMBER = 1001;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public record Item(UUID variantId, String productTitle, String variantTitle, @Nullable String sku, long unitPriceMinor,
                       int quantity, long lineTotalMinor) {
    }

    public record Event(String type, @Nullable UUID actorId, Map<String, Object> details, Instant createdAt) {
    }

    /**
     * Next order number for the store, gapless: the counter row is created on first use and then
     * incremented in ONE statement. The UPDATE row-locks the counter until the placing transaction
     * ends, so two concurrent orders get consecutive numbers, and a rolled-back order gives its
     * number back (unlike a sequence).
     */
    public long nextOrderNumber(StoreId storeId) {
        return jdbc.sql("""
                INSERT INTO store_counters (store_id, next_order_number) VALUES (:storeId, :second)
                ON CONFLICT (store_id) DO UPDATE SET next_order_number = store_counters.next_order_number + 1
                RETURNING next_order_number - 1
                """)
                .param("storeId", storeId.value())
                .param("second", FIRST_ORDER_NUMBER + 1)
                .query(Long.class)
                .single();
    }

    public void insertItems(StoreId storeId, UUID orderId, List<PlacedLine> lines) {
        for (PlacedLine line : lines) {
            jdbc.sql("""
                    INSERT INTO order_items (store_id, order_id, variant_id, product_title, variant_title, sku,
                                             unit_price_minor, quantity, line_total_minor)
                    VALUES (:storeId, :orderId, :variantId, :productTitle, :variantTitle, :sku, :unitPrice, :quantity, :lineTotal)
                    """)
                    .param("storeId", storeId.value())
                    .param("orderId", orderId)
                    .param("variantId", line.variantId())
                    .param("productTitle", line.productTitle())
                    .param("variantTitle", line.variantTitle())
                    .param("sku", line.sku())
                    .param("unitPrice", line.unitPriceMinor())
                    .param("quantity", line.quantity())
                    .param("lineTotal", line.lineTotalMinor())
                    .update();
        }
    }

    public List<Item> items(StoreId storeId, UUID orderId) {
        return jdbc.sql("""
                SELECT variant_id, product_title, variant_title, sku, unit_price_minor, quantity, line_total_minor
                  FROM order_items WHERE store_id = :storeId AND order_id = :orderId ORDER BY product_title, variant_id
                """)
                .param("storeId", storeId.value())
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Item(rs.getObject("variant_id", UUID.class), rs.getString("product_title"),
                        rs.getString("variant_title"), rs.getString("sku"), rs.getLong("unit_price_minor"), rs.getInt("quantity"),
                        rs.getLong("line_total_minor")))
                .list();
    }

    public void event(StoreId storeId, UUID orderId, String type, @Nullable UUID actorId, Map<String, Object> details, Instant now) {
        jdbc.sql("""
                INSERT INTO order_events (id, store_id, order_id, type, actor_id, details, created_at)
                VALUES (:id, :storeId, :orderId, :type, :actorId, CAST(:details AS jsonb), :now)
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("orderId", orderId)
                .param("type", type)
                .param("actorId", actorId)
                .param("details", jsonMapper.writeValueAsString(details))
                .param("now", Timestamp.from(now))
                .update();
    }

    public List<Event> events(StoreId storeId, UUID orderId) {
        return jdbc.sql("""
                SELECT type, actor_id, details::text AS details, created_at FROM order_events
                 WHERE store_id = :storeId AND order_id = :orderId ORDER BY created_at, id
                """)
                .param("storeId", storeId.value())
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Event(rs.getString("type"), rs.getObject("actor_id", UUID.class),
                        jsonMapper.readValue(rs.getString("details"), MAP), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** Overdue unpaid orders of one store, claimed without blocking other workers (ADR-011 pattern). */
    public List<UUID> claimOverdue(StoreId storeId, Instant cutoff, int batch) {
        return jdbc.sql("""
                SELECT id FROM orders
                 WHERE store_id = :storeId AND status = 'OPEN' AND financial_status = 'PENDING' AND payment_due_at < :cutoff
                 ORDER BY payment_due_at LIMIT :batch FOR UPDATE SKIP LOCKED
                """)
                .param("storeId", storeId.value())
                .param("cutoff", Timestamp.from(cutoff))
                .param("batch", batch)
                .query(UUID.class)
                .list();
    }

    public List<UUID> storesWithOverdueOrders(Instant cutoff, int maxStores) {
        return jdbc.sql("SELECT store_id FROM stores_with_overdue_orders(:cutoff, :max)")
                .param("cutoff", Timestamp.from(cutoff))
                .param("max", maxStores)
                .query(UUID.class)
                .list();
    }
}
