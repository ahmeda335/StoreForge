package dev.storeforge.catalog.internal.persistence;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Storefront search over ACTIVE products of one store (blueprint 13, EXP-018).
 * <ul>
 *   <li>Match: full text ({@code websearch_to_tsquery}) OR trigram word similarity of the title
 *       (typos), in {@code search_product_matches}: a SECURITY DEFINER function, because under RLS
 *       PostgreSQL won't use non-leakproof operators as index conditions (seconds instead of ms).
 *       It returns only ids and scores; product data is read here, under RLS.</li>
 *   <li>Rank: ts_rank_cd (title > vendor/type > description), then similarity, then newest.
 *       Empty query: everything, newest first.</li>
 *   <li>Facets are DISJUNCTIVE: vendor counts ignore the vendor filter (and type counts the type
 *       filter), so choosing one vendor still shows the others.</li>
 *   <li>ONE statement: matches computed once, then page, total and facets from them. Prices are
 *       computed for every match only under a price filter or sort; otherwise for the page.</li>
 * </ul>
 * Offset pagination, capped: relevance isn't a stable key for keyset pagination.
 */
@Repository
@RequiredArgsConstructor
public class ProductSearch {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

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

    public Result search(StoreId storeId, Criteria criteria) {
        boolean hasQuery = criteria.query() != null && !criteria.query().isBlank();
        String matches = hasQuery
                ? "SELECT product_id, rank, similarity FROM search_product_matches(:q)"
                : "SELECT p.id AS product_id, 0::real AS rank, 0::real AS similarity FROM products p WHERE p.store_id = :store AND p.status = 'ACTIVE'";
        String order = switch (criteria.sort()) {
            case "price_asc" -> "min_price, created_at DESC, id";
            case "price_desc" -> "max_price DESC, created_at DESC, id";
            case "newest" -> "created_at DESC, id";
            default -> hasQuery ? "rank DESC, similarity DESC, created_at DESC, id" : "created_at DESC, id";
        };
        String vendorFilter = "(CAST(:vendor AS text) IS NULL OR vendor = CAST(:vendor AS text))";
        String typeFilter = "(CAST(:type AS text) IS NULL OR product_type = CAST(:type AS text))";
        // Prices of EVERY match only when they decide membership or order; otherwise only the page's (EXP-018).
        boolean pricesForAll = criteria.minPriceMinor() != null || criteria.maxPriceMinor() != null || criteria.sort().startsWith("price");
        String base = pricesForAll ? """
                priced AS MATERIALIZED (   -- m is read ONCE: a second join to it lets a generic plan nest-loop m x m
                    SELECT m.product_id, m.rank, m.similarity, min(v.price_minor) AS min_price, max(v.price_minor) AS max_price
                      FROM m JOIN product_variants v ON v.store_id = :store AND v.product_id = m.product_id
                     GROUP BY m.product_id, m.rank, m.similarity),
                base AS MATERIALIZED (
                    SELECT p.id, p.handle, p.title, p.vendor, p.product_type, p.created_at, pr.min_price, pr.max_price, pr.rank, pr.similarity
                      FROM priced pr JOIN products p ON p.store_id = :store AND p.id = pr.product_id
                     WHERE (CAST(:minPrice AS bigint) IS NULL OR pr.max_price >= CAST(:minPrice AS bigint))
                       AND (CAST(:maxPrice AS bigint) IS NULL OR pr.min_price <= CAST(:maxPrice AS bigint))),
                """ : hasQuery ? """
                base AS MATERIALIZED (
                    SELECT p.id, p.handle, p.title, p.vendor, p.product_type, p.created_at, NULL::bigint AS min_price, NULL::bigint AS max_price,
                           m.rank, m.similarity
                      FROM m JOIN products p ON p.store_id = :store AND p.id = m.product_id),
                """ : """
                base AS MATERIALIZED (   -- browsing: nothing to rank, read the products directly (m stays unused)
                    SELECT p.id, p.handle, p.title, p.vendor, p.product_type, p.created_at, NULL::bigint AS min_price, NULL::bigint AS max_price,
                           0::real AS rank, 0::real AS similarity
                      FROM products p WHERE p.store_id = :store AND p.status = 'ACTIVE'),
                """;
        String sql = """
                WITH m AS MATERIALIZED (%s),
                %s
                filtered AS (SELECT * FROM base WHERE %s AND %s),
                page AS (SELECT f.id, f.handle, f.title, f.vendor, f.product_type, f.created_at, f.rank, f.similarity,
                                coalesce(f.min_price, pp.min_price) AS min_price, coalesce(f.max_price, pp.max_price) AS max_price
                           FROM (SELECT * FROM filtered ORDER BY %s LIMIT :limit OFFSET :offset) f
                          CROSS JOIN LATERAL (SELECT min(v.price_minor) AS min_price, max(v.price_minor) AS max_price FROM product_variants v
                                               WHERE v.store_id = :store AND v.product_id = f.id) pp)
                SELECT json_build_object(
                    'hits', coalesce((SELECT json_agg(json_build_object('id', id, 'handle', handle, 'title', title, 'vendor', vendor,
                                         'productType', product_type, 'minPrice', min_price, 'maxPrice', max_price) ORDER BY %s) FROM page), '[]'),
                    'total', (SELECT count(*) FROM filtered),
                    'vendors', coalesce((SELECT json_agg(json_build_object('value', value, 'count', c) ORDER BY c DESC, value) FROM
                                 (SELECT vendor AS value, count(*) AS c FROM base WHERE vendor IS NOT NULL AND %s GROUP BY vendor
                                   ORDER BY c DESC, vendor LIMIT 20) v), '[]'),
                    'types', coalesce((SELECT json_agg(json_build_object('value', value, 'count', c) ORDER BY c DESC, value) FROM
                               (SELECT product_type AS value, count(*) AS c FROM base WHERE product_type IS NOT NULL AND %s GROUP BY product_type
                                 ORDER BY c DESC, product_type LIMIT 20) t), '[]'))::text
                """.formatted(matches, base.stripTrailing(), vendorFilter, typeFilter, order, order, typeFilter, vendorFilter);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("store", storeId.value());
        params.put("q", hasQuery ? criteria.query() : "");
        params.put("minPrice", criteria.minPriceMinor());
        params.put("maxPrice", criteria.maxPriceMinor());
        params.put("vendor", criteria.vendor());
        params.put("type", criteria.productType());
        params.put("limit", criteria.limit());
        params.put("offset", criteria.offset());
        JsonNode json = jsonMapper.readTree(jdbc.sql(sql).params(params).query(String.class).single());
        List<Hit> hits = new ArrayList<>();
        json.get("hits").forEach(h -> hits.add(new Hit(UUID.fromString(h.get("id").asString()), h.get("handle").asString(), h.get("title").asString(),
                text(h, "vendor"), text(h, "productType"), h.get("minPrice").asLong(), h.get("maxPrice").asLong())));
        return new Result(hits, json.get("total").asLong(), facets(json.get("vendors")), facets(json.get("types")));
    }

    private static List<Facet> facets(JsonNode array) {
        List<Facet> facets = new ArrayList<>();
        array.forEach(f -> facets.add(new Facet(f.get("value").asString(), f.get("count").asInt())));
        return facets;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
