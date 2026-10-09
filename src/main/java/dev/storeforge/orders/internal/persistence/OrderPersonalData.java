package dev.storeforge.orders.internal.persistence;

import dev.storeforge.orders.internal.domain.OrderExceptions;
import dev.storeforge.shared.privacy.PersonalData;
import dev.storeforge.shared.tenancy.StoreId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Orders: export everything about the customer's orders; erasure keeps amounts, items, dates and country (accounting). */
@Component
@RequiredArgsConstructor
class OrderPersonalData implements PersonalData {

    private static final String MATCH = "store_id = :store AND (customer_id = :customer OR email = CAST(:email AS citext))";

    private final JdbcClient jdbc;

    @Override
    public String section() {
        return "orders";
    }

    @Override
    public void checkErasable(StoreId storeId, DataSubject subject) {
        int open = jdbc.sql("SELECT count(*) FROM orders WHERE " + MATCH + " AND status = 'OPEN'")
                .params(params(storeId, subject)).query(Integer.class).single();
        if (open > 0) {
            throw new OrderExceptions.CustomerHasOpenOrders(open);
        }
    }

    @Override
    public Object export(StoreId storeId, DataSubject subject) {
        List<Map<String, Object>> orders = jdbc.sql("""
                        SELECT id, number, email, currency, subtotal_minor, discount_minor, shipping_minor, tax_minor, total_minor, status,
                               financial_status, fulfillment_status, ship_name, ship_line1, ship_line2, ship_city, ship_region,
                               ship_postal_code, ship_country, created_at
                          FROM orders WHERE %s ORDER BY number
                        """.formatted(MATCH))
                .params(params(storeId, subject))
                .query((rs, n) -> {
                    Map<String, Object> order = new LinkedHashMap<>();
                    order.put("name", "#" + rs.getLong("number"));
                    order.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    order.put("email", rs.getString("email"));
                    order.put("status", rs.getString("status"));
                    order.put("financialStatus", rs.getString("financial_status"));
                    order.put("fulfillmentStatus", rs.getString("fulfillment_status"));
                    order.put("currency", rs.getString("currency").trim());
                    order.put("totalMinor", rs.getLong("total_minor"));
                    order.put("shippingAddress", Map.of("name", rs.getString("ship_name"), "line1", rs.getString("ship_line1"),
                            "line2", String.valueOf(rs.getString("ship_line2")), "city", rs.getString("ship_city"),
                            "postalCode", rs.getString("ship_postal_code"), "country", rs.getString("ship_country")));
                    order.put("items", jdbc.sql("""
                                    SELECT product_title, variant_title, quantity, unit_price_minor FROM order_items
                                     WHERE store_id = :store AND order_id = :order ORDER BY product_title
                                    """)
                            .param("store", storeId.value()).param("order", rs.getObject("id"))
                            .query((item, m) -> Map.of("product", item.getString("product_title"), "variant", item.getString("variant_title"),
                                    "quantity", item.getInt("quantity"), "unitPriceMinor", item.getLong("unit_price_minor")))
                            .list());
                    return order;
                })
                .list();
        return orders.isEmpty() ? null : orders;
    }

    @Override
    public void erase(StoreId storeId, DataSubject subject) {
        jdbc.sql("""
                UPDATE orders SET email = :replacement, ship_name = 'Erased', ship_line1 = 'Erased', ship_line2 = NULL, ship_city = 'Erased',
                                  ship_region = NULL, ship_postal_code = 'Erased'
                 WHERE %s
                """.formatted(MATCH))
                .params(params(storeId, subject)).param("replacement", subject.replacementEmail())
                .update();
    }

    private static Map<String, Object> params(StoreId storeId, DataSubject subject) {
        return Map.of("store", storeId.value(), "customer", subject.customerId(), "email", subject.email());
    }
}
