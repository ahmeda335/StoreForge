package dev.storeforge.catalog.internal.persistence;

import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read models for list screens, written as plain SQL that returns exactly what the screen shows.
 * Loading full JPA aggregates for a list of 25 products would mean 1 query for products + 25 lazy
 * loads of variants (the N+1 problem). Here, the admin list is ONE query (variant aggregates via a
 * LATERAL subquery); the storefront list is TWO (a page of products, then all their variants).
 * Either way, the number of queries does not grow with the page size.
 */
@Repository
public class ProductQueries {

    private final JdbcClient jdbc;

    ProductQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AdminFilter(@Nullable String status, @Nullable String vendor, @Nullable String titleContains, boolean ascending) {
    }

    public record ProductSummaryRow(UUID id, String handle, String title, String status, @Nullable String vendor,
                                    int variantCount, long minPriceMinor, long maxPriceMinor,
                                    Instant createdAt, Instant updatedAt) {
    }

    public record ProductRow(UUID id, String handle, String title, String description, @Nullable String vendor,
                             @Nullable String productType, List<String> optionNames, Instant createdAt) {
    }

    public record VariantRow(UUID id, UUID productId, @Nullable String sku, long priceMinor, @Nullable Long compareAtPriceMinor,
                             List<String> optionValues, int position) {
    }

    public List<ProductSummaryRow> adminPage(StoreId storeId, AdminFilter filter, int limitPlusOne, @Nullable Cursor after) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.handle, p.title, p.status, p.vendor, p.created_at, p.updated_at,
                       v.variant_count, v.min_price, v.max_price
                  FROM products p
                  CROSS JOIN LATERAL (
                       SELECT count(*) AS variant_count, coalesce(min(price_minor), 0) AS min_price, coalesce(max(price_minor), 0) AS max_price
                         FROM product_variants pv WHERE pv.store_id = p.store_id AND pv.product_id = p.id) v
                 WHERE p.store_id = :storeId
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId.value());
        if (filter.status() != null) {
            sql.append(" AND p.status = :status");
            params.put("status", filter.status());
        }
        if (filter.vendor() != null) {
            sql.append(" AND p.vendor = :vendor");
            params.put("vendor", filter.vendor());
        }
        if (filter.titleContains() != null) {
            // User input inside LIKE: escape the wildcards so "%" searches for a literal percent sign.
            sql.append(" AND p.title ILIKE :title ESCAPE '\\'");
            params.put("title", "%" + filter.titleContains().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String direction = filter.ascending() ? "ASC" : "DESC";
        if (after != null) {
            sql.append(filter.ascending() ? " AND (p.created_at, p.id) > (:afterTime, :afterId)" : " AND (p.created_at, p.id) < (:afterTime, :afterId)");
            params.put("afterTime", Timestamp.from(after.timestamp()));
            params.put("afterId", after.id());
        }
        sql.append(" ORDER BY p.created_at ").append(direction).append(", p.id ").append(direction).append(" LIMIT :limit");
        params.put("limit", limitPlusOne);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> new ProductSummaryRow(rs.getObject("id", UUID.class), rs.getString("handle"),
                        rs.getString("title"), rs.getString("status"), rs.getString("vendor"), rs.getInt("variant_count"),
                        rs.getLong("min_price"), rs.getLong("max_price"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public List<ProductRow> activePage(StoreId storeId, int limitPlusOne, @Nullable Cursor after) {
        String keyset = after == null ? "" : "AND (created_at, id) < (:afterTime, :afterId)";
        var statement = jdbc.sql("""
                SELECT id, handle, title, description, vendor, product_type, option_names, created_at
                  FROM products
                 WHERE store_id = :storeId AND status = 'ACTIVE' %s
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """.formatted(keyset))
                .param("storeId", storeId.value())
                .param("limit", limitPlusOne);
        if (after != null) {
            statement = statement.param("afterTime", Timestamp.from(after.timestamp())).param("afterId", after.id());
        }
        return statement.query(ProductQueries::productRow).list();
    }

    public @Nullable ProductRow activeByHandle(StoreId storeId, String handle) {
        return jdbc.sql("""
                SELECT id, handle, title, description, vendor, product_type, option_names, created_at
                  FROM products WHERE store_id = :storeId AND handle = :handle AND status = 'ACTIVE'
                """)
                .param("storeId", storeId.value())
                .param("handle", handle)
                .query(ProductQueries::productRow)
                .optional()
                .orElse(null);
    }

    /** All variants of the given products in ONE query, grouped by product id. */
    public Map<UUID, List<VariantRow>> variantsOf(StoreId storeId, Collection<UUID> productIds) {
        Map<UUID, List<VariantRow>> byProduct = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return byProduct;
        }
        jdbc.sql("""
                SELECT id, product_id, sku, price_minor, compare_at_price_minor, option1, option2, option3, position
                  FROM product_variants
                 WHERE store_id = :storeId AND product_id = ANY(:productIds)
                 ORDER BY product_id, position
                """)
                .param("storeId", storeId.value())
                .param("productIds", productIds.toArray(UUID[]::new))
                .query((rs, rowNum) -> variantRow(rs))
                .list()
                .forEach(row -> byProduct.computeIfAbsent(row.productId(), id -> new ArrayList<>()).add(row));
        return byProduct;
    }

    private static ProductRow productRow(ResultSet rs, int rowNum) throws SQLException {
        return new ProductRow(rs.getObject("id", UUID.class), rs.getString("handle"), rs.getString("title"),
                rs.getString("description"), rs.getString("vendor"), rs.getString("product_type"),
                List.of((String[]) rs.getArray("option_names").getArray()), rs.getTimestamp("created_at").toInstant());
    }

    private static VariantRow variantRow(ResultSet rs) throws SQLException {
        List<String> options = new ArrayList<>(3);
        for (String column : new String[] {"option1", "option2", "option3"}) {
            String value = rs.getString(column);
            if (value != null) {
                options.add(value);
            }
        }
        return new VariantRow(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("sku"),
                rs.getLong("price_minor"), rs.getObject("compare_at_price_minor", Long.class), options, rs.getInt("position"));
    }
}
