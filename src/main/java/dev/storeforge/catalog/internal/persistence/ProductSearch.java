package dev.storeforge.catalog.internal.persistence;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Storefront search over ACTIVE products of one store (blueprint 13).
 * <ul>
 *   <li>Match: full-text ({@code websearch_to_tsquery}: quotes, OR, -exclusion) OR trigram word
 *       similarity of the title (typos). Empty query: everything, newest first.</li>
 *   <li>Rank: ts_rank_cd (weights title > vendor/type > description), then similarity, then newest.</li>
 *   <li>Facets are DISJUNCTIVE: vendor counts ignore the vendor filter (and type counts the type
 *       filter), so choosing "Acme" still shows the other vendors to switch to.</li>
 * </ul>
 * Offset pagination, capped: relevance order isn't a stable key for keyset pagination, and nobody
 * reads page 50 of search results.
 */
@Repository
@RequiredArgsConstructor
public class ProductSearch {

    private final JdbcClient jdbc;

    public record Criteria(@Nullable String query, @Nullable String vendor, @Nullable String productType, @Nullable Long minPriceMinor,
                           @Nullable Long maxPriceMinor, String sort, int limit, int offset) {
    }

    public record Hit(UUID id, String handle, String title, @Nullable String vendor, @Nullable String productType, long minPriceMinor,
                      long maxPriceMinor) {
    }

    public record Facet(String value, int count) {
    }

    public record Result(List<Hit> hits, long total, List<Facet> vendors, List<Facet> productTypes) {
    }

    private static final String BASE = """
            WITH q AS (SELECT websearch_to_tsquery('product_search', coalesce(CAST(:q AS text), '')) AS tsq,
                              search_normalize(coalesce(CAST(:q AS text), '')) AS norm),
            base AS (
                SELECT p.id, p.handle, p.title, p.vendor, p.product_type, p.created_at, pr.min_price, pr.max_price,
                       CASE WHEN :hasQuery THEN ts_rank_cd(p.search_vector, q.tsq) ELSE 0 END AS rank,
                       CASE WHEN :hasQuery THEN word_similarity(q.norm, search_normalize(p.title)) ELSE 0 END AS sim
                  FROM products p
                 CROSS JOIN q
                 CROSS JOIN LATERAL (SELECT min(v.price_minor) AS min_price, max(v.price_minor) AS max_price
                                       FROM product_variants v WHERE v.store_id = p.store_id AND v.product_id = p.id) pr
                 WHERE p.store_id = :store AND p.status = 'ACTIVE'
                   AND (NOT :hasQuery OR p.search_vector @@ q.tsq OR q.norm <% search_normalize(p.title))
                   AND (CAST(:minPrice AS bigint) IS NULL OR pr.max_price >= CAST(:minPrice AS bigint))
                   AND (CAST(:maxPrice AS bigint) IS NULL OR pr.min_price <= CAST(:maxPrice AS bigint))
            )
            """;

    public Result search(StoreId storeId, Criteria criteria) {
        boolean hasQuery = criteria.query() != null && !criteria.query().isBlank();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("store", storeId.value());
        params.put("q", hasQuery ? criteria.query() : null);
        params.put("hasQuery", hasQuery);
        params.put("minPrice", criteria.minPriceMinor());
        params.put("maxPrice", criteria.maxPriceMinor());
        params.put("vendor", criteria.vendor());
        params.put("type", criteria.productType());
        params.put("limit", criteria.limit());
        params.put("offset", criteria.offset());
        String vendorFilter = " (CAST(:vendor AS text) IS NULL OR vendor = CAST(:vendor AS text)) ";
        String typeFilter = " (CAST(:type AS text) IS NULL OR product_type = CAST(:type AS text)) ";
        String order = switch (criteria.sort()) {
            case "price_asc" -> "min_price, created_at DESC, id";
            case "price_desc" -> "max_price DESC, created_at DESC, id";
            case "newest" -> "created_at DESC, id";
            default -> hasQuery ? "rank DESC, sim DESC, created_at DESC, id" : "created_at DESC, id";
        };
        long[] total = {0};
        List<Hit> hits = jdbc.sql(BASE + "SELECT *, count(*) OVER () AS total FROM base WHERE" + vendorFilter + "AND" + typeFilter
                        + "ORDER BY " + order + " LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, n) -> {
                    total[0] = rs.getLong("total");
                    return new Hit(rs.getObject("id", UUID.class), rs.getString("handle"), rs.getString("title"), rs.getString("vendor"),
                            rs.getString("product_type"), rs.getLong("min_price"), rs.getLong("max_price"));
                })
                .list();
        List<Facet> vendors = facet(BASE + "SELECT vendor AS value, count(*) AS c FROM base WHERE vendor IS NOT NULL AND" + typeFilter
                + "GROUP BY vendor ORDER BY c DESC, vendor LIMIT 20", params);
        List<Facet> types = facet(BASE + "SELECT product_type AS value, count(*) AS c FROM base WHERE product_type IS NOT NULL AND" + vendorFilter
                + "GROUP BY product_type ORDER BY c DESC, product_type LIMIT 20", params);
        if (hits.isEmpty() && criteria.offset() > 0) {
            total[0] = jdbc.sql(BASE + "SELECT count(*) FROM base WHERE" + vendorFilter + "AND" + typeFilter).params(params).query(Long.class).single();
        }
        return new Result(hits, total[0], vendors, types);
    }

    private List<Facet> facet(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params).query((rs, n) -> new Facet(rs.getString("value"), rs.getInt("c"))).list();
    }
}
