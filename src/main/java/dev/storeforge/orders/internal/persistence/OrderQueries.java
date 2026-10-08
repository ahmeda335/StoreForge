package dev.storeforge.orders.internal.persistence;

import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Merchant order list: one query, allow-listed filters, keyset pagination. */
@Repository
@RequiredArgsConstructor
public class OrderQueries {

    private final JdbcClient jdbc;

    public record Filter(@Nullable String status, @Nullable String financialStatus, @Nullable String fulfillmentStatus,
                         @Nullable Long number, @Nullable String emailPrefix) {
    }

    public record Row(UUID id, long number, String email, String currency, long totalMinor, String status,
                      String financialStatus, String fulfillmentStatus, int itemCount, Instant createdAt, Instant updatedAt) {
    }

    /**
     * Sync listing for integrations: orders changed after a point in time, oldest change first,
     * keyset on (updated_at, id). A client stores the last cursor and resumes exactly there.
     */
    public List<Row> changedSince(StoreId storeId, Instant updatedAfter, int limitPlusOne, @Nullable Cursor after) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE o.store_id = :storeId AND o.updated_at > :updatedAfter");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId.value());
        params.put("updatedAfter", Timestamp.from(updatedAfter));
        if (after != null) {
            sql.append(" AND (o.updated_at, o.id) > (:afterTime, :afterId)");
            params.put("afterTime", Timestamp.from(after.timestamp()));
            params.put("afterId", after.id());
        }
        sql.append(" ORDER BY o.updated_at, o.id LIMIT :limit");
        params.put("limit", limitPlusOne);
        return jdbc.sql(sql.toString()).params(params).query(OrderQueries::map).list();
    }

    public List<Row> page(StoreId storeId, Filter filter, int limitPlusOne, @Nullable Cursor after) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE o.store_id = :storeId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId.value());
        if (filter.status() != null) {
            sql.append(" AND o.status = :status");
            params.put("status", filter.status());
        }
        if (filter.financialStatus() != null) {
            sql.append(" AND o.financial_status = :financialStatus");
            params.put("financialStatus", filter.financialStatus());
        }
        if (filter.fulfillmentStatus() != null) {
            sql.append(" AND o.fulfillment_status = :fulfillmentStatus");
            params.put("fulfillmentStatus", filter.fulfillmentStatus());
        }
        if (filter.number() != null) {
            sql.append(" AND o.number = :number");
            params.put("number", filter.number());
        }
        if (filter.emailPrefix() != null) {
            sql.append(" AND o.email LIKE :email ESCAPE '\\'");
            params.put("email", filter.emailPrefix().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (after != null) {
            sql.append(" AND (o.created_at, o.id) < (:afterTime, :afterId)");
            params.put("afterTime", Timestamp.from(after.timestamp()));
            params.put("afterId", after.id());
        }
        sql.append(" ORDER BY o.created_at DESC, o.id DESC LIMIT :limit");
        params.put("limit", limitPlusOne);
        return jdbc.sql(sql.toString()).params(params).query(OrderQueries::map).list();
    }

    private static final String SELECT = """
            SELECT o.id, o.number, o.email, o.currency, o.total_minor, o.status, o.financial_status, o.fulfillment_status,
                   o.created_at, o.updated_at, (SELECT coalesce(sum(quantity), 0) FROM order_items i
                                                 WHERE i.store_id = o.store_id AND i.order_id = o.id) AS item_count
              FROM orders o""";

    private static Row map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getLong("number"), rs.getString("email"),
                rs.getString("currency").trim(), rs.getLong("total_minor"), rs.getString("status"),
                rs.getString("financial_status"), rs.getString("fulfillment_status"), rs.getInt("item_count"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
