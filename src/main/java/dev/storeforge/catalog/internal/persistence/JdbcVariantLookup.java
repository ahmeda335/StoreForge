package dev.storeforge.catalog.internal.persistence;

import dev.storeforge.catalog.SellableVariant;
import dev.storeforge.catalog.VariantLookup;
import dev.storeforge.shared.tenancy.StoreId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class JdbcVariantLookup implements VariantLookup {

    private final JdbcClient jdbc;

    /** One query for any number of variants (callers price whole carts). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, SellableVariant> find(StoreId storeId, Collection<UUID> variantIds) {
        Map<UUID, SellableVariant> result = new HashMap<>();
        if (variantIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                SELECT v.id, v.product_id, p.title AS product_title, v.sku, v.price_minor, v.option1, v.option2, v.option3,
                       p.status
                  FROM product_variants v JOIN products p ON p.store_id = v.store_id AND p.id = v.product_id
                 WHERE v.store_id = :storeId AND v.id = ANY(:ids)
                """)
                .param("storeId", storeId.value())
                .param("ids", variantIds.toArray(UUID[]::new))
                .query(rs -> {
                    List<String> options = new ArrayList<>(3);
                    for (String column : new String[] {"option1", "option2", "option3"}) {
                        if (rs.getString(column) != null) {
                            options.add(rs.getString(column));
                        }
                    }
                    UUID id = rs.getObject("id", UUID.class);
                    result.put(id, new SellableVariant(id, rs.getObject("product_id", UUID.class), rs.getString("product_title"),
                            options.isEmpty() ? "Default" : String.join(" / ", options), rs.getString("sku"),
                            rs.getLong("price_minor"), "ACTIVE".equals(rs.getString("status"))));
                });
        return result;
    }
}
