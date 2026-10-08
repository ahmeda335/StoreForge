package dev.storeforge.discounts.internal;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Discounts and redemptions (tenant data, RLS); runs in the caller's tenant transaction. */
@Repository
@RequiredArgsConstructor
class DiscountStore {

    private final JdbcClient jdbc;

    record Discount(UUID id, String code, String kind, long value, long minimumSubtotalMinor, Instant startsAt, @Nullable Instant endsAt,
                    @Nullable Integer usageLimit, int usageCount, boolean oncePerCustomer, String status, Instant createdAt) {
        DiscountRules.Rule rule() {
            return new DiscountRules.Rule(code, kind, value, minimumSubtotalMinor, startsAt, endsAt, usageLimit, usageCount, status);
        }
    }

    UUID insert(StoreId storeId, String code, String kind, long value, long minimumSubtotal, Instant startsAt, @Nullable Instant endsAt,
                @Nullable Integer usageLimit, boolean oncePerCustomer, Instant now) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO discounts (id, store_id, code, kind, value, minimum_subtotal_minor, starts_at, ends_at, usage_limit,
                                       once_per_customer, status, created_at, updated_at)
                VALUES (:id, :store, :code, :kind, :value, :minimum, :startsAt, :endsAt, :usageLimit, :once, 'ACTIVE', :now, :now)
                """)
                .param("id", id).param("store", storeId.value()).param("code", code).param("kind", kind).param("value", value)
                .param("minimum", minimumSubtotal).param("startsAt", Timestamp.from(startsAt))
                .param("endsAt", endsAt == null ? null : Timestamp.from(endsAt)).param("usageLimit", usageLimit)
                .param("once", oncePerCustomer).param("now", Timestamp.from(now))
                .update();
        return id;
    }

    Optional<Discount> byCode(StoreId storeId, String code) {
        // CAST: "citext = text" resolves to the case-SENSITIVE text operator; the parameter must be citext too.
        return jdbc.sql("SELECT * FROM discounts WHERE store_id = :store AND code = CAST(:code AS citext)").param("store", storeId.value()).param("code", code)
                .query(DiscountStore::map).optional();
    }

    Optional<Discount> byId(StoreId storeId, UUID id) {
        return jdbc.sql("SELECT * FROM discounts WHERE store_id = :store AND id = :id").param("store", storeId.value()).param("id", id)
                .query(DiscountStore::map).optional();
    }

    List<Discount> list(StoreId storeId) {
        return jdbc.sql("SELECT * FROM discounts WHERE store_id = :store ORDER BY created_at DESC, id DESC").param("store", storeId.value())
                .query(DiscountStore::map).list();
    }

    void update(StoreId storeId, UUID id, String status, @Nullable Instant endsAt, @Nullable Integer usageLimit, Instant now) {
        jdbc.sql("UPDATE discounts SET status = :status, ends_at = :endsAt, usage_limit = :usageLimit, updated_at = :now WHERE store_id = :store AND id = :id")
                .param("status", status).param("endsAt", endsAt == null ? null : Timestamp.from(endsAt)).param("usageLimit", usageLimit)
                .param("now", Timestamp.from(now)).param("store", storeId.value()).param("id", id).update();
    }

    /**
     * Takes one use if, AT THIS MOMENT, the code is active, in its window and not used up. One
     * statement: the row lock makes concurrent takers queue, and each re-checks the condition after
     * the previous one committed. Exactly {@code usage_limit} of them can win (P5's oversell pattern).
     */
    boolean takeUse(StoreId storeId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE discounts SET usage_count = usage_count + 1, updated_at = :now
                 WHERE store_id = :store AND id = :id AND status = 'ACTIVE' AND starts_at <= :now
                   AND (ends_at IS NULL OR ends_at > :now) AND (usage_limit IS NULL OR usage_count < usage_limit)
                """)
                .param("now", Timestamp.from(now)).param("store", storeId.value()).param("id", id).update() == 1;
    }

    boolean usedByCustomer(StoreId storeId, UUID discountId, String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM discount_redemptions WHERE store_id = :store AND discount_id = :id"
                        + " AND customer_key = CAST(:email AS citext))")
                .param("store", storeId.value()).param("id", discountId).param("email", email).query(Boolean.class).single();
    }

    void insertRedemption(StoreId storeId, UUID discountId, UUID checkoutId, UUID orderId, long amount, @Nullable String customerKey, Instant now) {
        jdbc.sql("""
                INSERT INTO discount_redemptions (id, store_id, discount_id, checkout_id, order_id, amount_minor, customer_key, redeemed_at)
                VALUES (:id, :store, :discount, :checkout, :order, :amount, :customer, :now)
                """)
                .param("id", Ids.newId()).param("store", storeId.value()).param("discount", discountId).param("checkout", checkoutId)
                .param("order", orderId).param("amount", amount).param("customer", customerKey).param("now", Timestamp.from(now)).update();
    }

    /** Gives the use back for a cancelled order. Deleting first makes it idempotent: a redelivery finds nothing. */
    boolean releaseForOrder(StoreId storeId, UUID orderId) {
        return jdbc.sql("""
                WITH released AS (DELETE FROM discount_redemptions WHERE store_id = :store AND order_id = :order RETURNING discount_id)
                UPDATE discounts d SET usage_count = d.usage_count - 1 FROM released r WHERE d.store_id = :store AND d.id = r.discount_id
                """)
                .param("store", storeId.value()).param("order", orderId).update() == 1;
    }

    private static Discount map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp ends = rs.getTimestamp("ends_at");
        return new Discount(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("kind"), rs.getLong("value"),
                rs.getLong("minimum_subtotal_minor"), rs.getTimestamp("starts_at").toInstant(), ends == null ? null : ends.toInstant(),
                rs.getObject("usage_limit", Integer.class), rs.getInt("usage_count"), rs.getBoolean("once_per_customer"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }
}
