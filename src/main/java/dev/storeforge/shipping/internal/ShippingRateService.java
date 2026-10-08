package dev.storeforge.shipping.internal;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.shared.error.StoreForgeException;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.shipping.ShippingRates;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ShippingRateService implements ShippingRates {

    private final JdbcClient jdbc;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final Clock clock;

    record StoredRate(UUID id, String name, List<String> countries, int minWeightGrams, @Nullable Integer maxWeightGrams, long priceMinor,
                      @Nullable Long freeOverMinor, boolean active) {
        RateRules.Rate rule() {
            return new RateRules.Rate(countries, minWeightGrams, maxWeightGrams, priceMinor, freeOverMinor, active);
        }
    }

    record NewRate(String name, List<String> countries, int minWeightGrams, @Nullable Integer maxWeightGrams, long priceMinor,
                   @Nullable Long freeOverMinor) {
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean configured(StoreId storeId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM shipping_rates WHERE store_id = :store AND active)").param("store", storeId.value())
                .query(Boolean.class).single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<Quote> quotes(StoreId storeId, String country, int weightGrams, long subtotalAfterDiscountMinor) {
        return rates(storeId).stream()
                .filter(rate -> RateRules.applies(rate.rule(), country, weightGrams))
                .map(rate -> new Quote(rate.id(), rate.name(), RateRules.price(rate.rule(), subtotalAfterDiscountMinor)))
                .sorted(Comparator.comparingLong(Quote::priceMinor).thenComparing(Quote::name))
                .toList();
    }

    StoredRate create(UUID userId, StoreId storeId, NewRate rate) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.STORE_MANAGE);
            UUID id = Ids.newId();
            try {
                jdbc.sql("""
                        INSERT INTO shipping_rates (id, store_id, name, countries, min_weight_grams, max_weight_grams, price_minor, free_over_minor,
                                                    created_at, updated_at)
                        VALUES (:id, :store, :name, CAST(:countries AS text[]), :min, :max, :price, :free, :now, :now)
                        """)
                        .param("id", id).param("store", storeId.value()).param("name", rate.name().trim())
                        .param("countries", rate.countries().stream().map(String::toUpperCase).distinct().sorted().toArray(String[]::new))
                        .param("min", rate.minWeightGrams()).param("max", rate.maxWeightGrams()).param("price", rate.priceMinor())
                        .param("free", rate.freeOverMinor()).param("now", Timestamp.from(clock.instant())).update();
            } catch (DataIntegrityViolationException e) {
                throw new InvalidRate();
            }
            audit.recordUserAction(storeId, userId, "shipping_rate.created", "shipping_rate", id.toString(), Map.of("name", rate.name().trim()));
            return find(storeId, id);
        });
    }

    List<StoredRate> list(UUID userId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.STORE_MANAGE);
            return rates(storeId);
        });
    }

    StoredRate setActive(UUID userId, StoreId storeId, UUID id, boolean active) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.STORE_MANAGE);
            int updated = jdbc.sql("UPDATE shipping_rates SET active = :active, updated_at = :now WHERE store_id = :store AND id = :id")
                    .param("active", active).param("now", Timestamp.from(clock.instant())).param("store", storeId.value()).param("id", id).update();
            if (updated == 0) {
                throw new RateNotFound();
            }
            audit.recordUserAction(storeId, userId, "shipping_rate.updated", "shipping_rate", id.toString(), Map.of("active", active));
            return find(storeId, id);
        });
    }

    private List<StoredRate> rates(StoreId storeId) {
        return jdbc.sql("SELECT * FROM shipping_rates WHERE store_id = :store ORDER BY name, min_weight_grams").param("store", storeId.value())
                .query(ShippingRateService::map).list();
    }

    private StoredRate find(StoreId storeId, UUID id) {
        return jdbc.sql("SELECT * FROM shipping_rates WHERE store_id = :store AND id = :id").param("store", storeId.value()).param("id", id)
                .query(ShippingRateService::map).optional().orElseThrow(RateNotFound::new);
    }

    private static StoredRate map(ResultSet rs, int rowNum) throws SQLException {
        return new StoredRate(rs.getObject("id", UUID.class), rs.getString("name"), Arrays.asList((String[]) rs.getArray("countries").getArray()),
                rs.getInt("min_weight_grams"), rs.getObject("max_weight_grams", Integer.class), rs.getLong("price_minor"),
                rs.getObject("free_over_minor", Long.class), rs.getBoolean("active"));
    }

    static class RateNotFound extends StoreForgeException {
        RateNotFound() {
            super(HttpStatus.NOT_FOUND, "shipping_rate_not_found", "Shipping rate not found.");
        }
    }

    static class InvalidRate extends StoreForgeException {
        InvalidRate() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_shipping_rate",
                    "Invalid shipping rate: price ≥ 0, weight band [min, max) with max > min, free-over amount > 0.");
        }
    }
}
