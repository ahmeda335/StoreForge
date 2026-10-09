package dev.storeforge.discounts.internal;

import dev.storeforge.shared.privacy.PersonalData;
import dev.storeforge.shared.tenancy.StoreId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Redemptions remember WHO used a code (the email, for once-per-customer codes). After erasure the
 * key no longer identifies anyone, so an erased customer could use such a code again: an accepted
 * cost of erasure (ADR-027).
 */
@Component
@RequiredArgsConstructor
class DiscountPersonalData implements PersonalData {

    private final JdbcClient jdbc;

    @Override
    public String section() {
        return "discountRedemptions";
    }

    @Override
    public @Nullable Object export(StoreId storeId, DataSubject subject) {
        var codes = jdbc.sql("""
                        SELECT d.code FROM discount_redemptions r JOIN discounts d ON d.store_id = r.store_id AND d.id = r.discount_id
                         WHERE r.store_id = :store AND r.customer_key = CAST(:email AS citext) ORDER BY r.redeemed_at
                        """)
                .param("store", storeId.value()).param("email", subject.email()).query(String.class).list();
        return codes.isEmpty() ? null : Map.of("codesUsed", codes);
    }

    @Override
    public void erase(StoreId storeId, DataSubject subject) {
        jdbc.sql("UPDATE discount_redemptions SET customer_key = 'erased:' || id WHERE store_id = :store AND customer_key = CAST(:email AS citext)")
                .param("store", storeId.value()).param("email", subject.email()).update();
    }
}
