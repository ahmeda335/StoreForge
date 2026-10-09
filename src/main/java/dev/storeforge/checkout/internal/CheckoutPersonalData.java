package dev.storeforge.checkout.internal;

import dev.storeforge.shared.privacy.PersonalData;
import dev.storeforge.shared.tenancy.StoreId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Checkouts hold an email and an address; completed ones are also echoed in stored idempotency
 * responses (the completion response contains them), which are deleted: a replay after erasure
 * would otherwise return the erased data.
 */
@Component
@RequiredArgsConstructor
class CheckoutPersonalData implements PersonalData {

    private static final String MATCH = "store_id = :store AND (customer_id = :customer OR email = CAST(:email AS citext))";

    private final JdbcClient jdbc;

    @Override
    public String section() {
        return "checkouts";
    }

    @Override
    public @Nullable Object export(StoreId storeId, DataSubject subject) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, status, email, ship_name, ship_line1, ship_city, ship_postal_code, ship_country FROM checkouts WHERE %s
                        """.formatted(MATCH))
                .params(Map.of("store", storeId.value(), "customer", subject.customerId(), "email", subject.email()))
                .query((rs, n) -> Map.<String, Object>of("id", rs.getString("id"), "status", rs.getString("status"),
                        "email", String.valueOf(rs.getString("email")), "shipName", String.valueOf(rs.getString("ship_name")),
                        "shipLine1", String.valueOf(rs.getString("ship_line1")), "shipCity", String.valueOf(rs.getString("ship_city")),
                        "shipPostalCode", String.valueOf(rs.getString("ship_postal_code")), "shipCountry", String.valueOf(rs.getString("ship_country"))))
                .list();
        return rows.isEmpty() ? null : rows;
    }

    @Override
    public void erase(StoreId storeId, DataSubject subject) {
        Map<String, Object> params = Map.of("store", storeId.value(), "customer", subject.customerId(), "email", subject.email(),
                "replacement", subject.replacementEmail());
        jdbc.sql("""
                DELETE FROM idempotency_keys
                 WHERE store_id = :store
                   AND EXISTS (SELECT 1 FROM checkouts c WHERE c.store_id = :store AND (c.customer_id = :customer OR c.email = CAST(:email AS citext))
                                  AND idempotency_keys.response_body LIKE '%' || c.id::text || '%')
                """).params(params).update();
        jdbc.sql("""
                UPDATE checkouts SET email = :replacement, ship_name = NULL, ship_line1 = NULL, ship_line2 = NULL, ship_city = NULL,
                                     ship_region = NULL, ship_postal_code = NULL
                 WHERE %s
                """.formatted(MATCH)).params(params).update();
    }
}
