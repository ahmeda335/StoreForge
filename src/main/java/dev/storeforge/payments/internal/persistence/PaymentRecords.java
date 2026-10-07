package dev.storeforge.payments.internal.persistence;

import dev.storeforge.payments.internal.domain.Payment;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL around payments: race-free creation, the operation log, provider event dedup, reconciliation. */
@Repository
@RequiredArgsConstructor
public class PaymentRecords {

    private final JdbcClient jdbc;

    public record Operation(String kind, String status, int attempts, @Nullable Long amountMinor, @Nullable String providerReference,
                            @Nullable String errorCode, Instant updatedAt) {
    }

    /**
     * One payment per order even when two "start payment" requests race: the second INSERT waits
     * for the first to commit, then does nothing ({@code uq_payments_store_order}).
     */
    public void insertIfAbsent(Payment payment) {
        jdbc.sql("""
                INSERT INTO payments (id, store_id, order_id, provider, amount_minor, currency, status, created_at, updated_at, version)
                VALUES (:id, :storeId, :orderId, :provider, :amount, :currency, 'CREATED', :now, :now, 0)
                ON CONFLICT (store_id, order_id) DO NOTHING
                """)
                .param("id", payment.id())
                .param("storeId", payment.storeId())
                .param("orderId", payment.orderId())
                .param("provider", payment.provider())
                .param("amount", payment.amountMinor())
                .param("currency", payment.currency())
                .param("now", Timestamp.from(payment.createdAt()))
                .update();
    }

    /**
     * Recorded and COMMITTED before the provider call. A crash or timeout leaves IN_PROGRESS or
     * UNKNOWN behind: evidence that the provider may have acted, to be resolved by a retry with the
     * same key. Repeated attempts with the same key update the same row.
     */
    public void beginOperation(StoreId storeId, UUID paymentId, String kind, String idempotencyKey, @Nullable Long amountMinor,
                               Instant now) {
        jdbc.sql("""
                INSERT INTO payment_operations (id, store_id, payment_id, kind, idempotency_key, status, amount_minor, created_at, updated_at)
                VALUES (:id, :storeId, :paymentId, :kind, :key, 'IN_PROGRESS', :amount, :now, :now)
                ON CONFLICT (store_id, idempotency_key) DO UPDATE
                   SET status = 'IN_PROGRESS', attempts = payment_operations.attempts + 1, error_code = NULL, updated_at = :now
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("paymentId", paymentId)
                .param("kind", kind)
                .param("key", idempotencyKey)
                .param("amount", amountMinor)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void finishOperation(StoreId storeId, String idempotencyKey, String status, @Nullable String providerReference,
                                @Nullable String errorCode, Instant now) {
        jdbc.sql("""
                UPDATE payment_operations SET status = :status, provider_reference = coalesce(:ref, provider_reference),
                       error_code = :error, updated_at = :now
                 WHERE store_id = :storeId AND idempotency_key = :key
                """)
                .param("status", status)
                .param("ref", providerReference)
                .param("error", errorCode)
                .param("now", Timestamp.from(now))
                .param("storeId", storeId.value())
                .param("key", idempotencyKey)
                .update();
    }

    public List<Operation> operations(StoreId storeId, UUID paymentId) {
        return jdbc.sql("""
                SELECT kind, status, attempts, amount_minor, provider_reference, error_code, updated_at
                  FROM payment_operations WHERE store_id = :storeId AND payment_id = :paymentId ORDER BY created_at
                """)
                .param("storeId", storeId.value())
                .param("paymentId", paymentId)
                .query((rs, rowNum) -> new Operation(rs.getString("kind"), rs.getString("status"), rs.getInt("attempts"),
                        rs.getObject("amount_minor", Long.class), rs.getString("provider_reference"), rs.getString("error_code"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    /** Webhook deduplication: the primary key makes a redelivered event a no-op. @return true if new */
    public boolean recordProviderEvent(String provider, String eventId, String type, @Nullable UUID storeId, String payload,
                                       Instant now) {
        return jdbc.sql("""
                INSERT INTO provider_events (provider, event_id, type, store_id, payload, received_at)
                VALUES (:provider, :eventId, :type, :storeId, CAST(:payload AS jsonb), :now)
                ON CONFLICT (provider, event_id) DO NOTHING
                """)
                .param("provider", provider)
                .param("eventId", eventId)
                .param("type", type)
                .param("storeId", storeId)
                .param("payload", payload)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    public int deleteProviderEventsBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM provider_events WHERE received_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update();
    }

    /** Cross-tenant: store ids only, through the definer function (ADR-011). */
    public List<UUID> storesWithStalePayments(Instant cutoff, int maxStores) {
        return jdbc.sql("SELECT store_id FROM stores_with_stale_payments(:cutoff, :max)")
                .param("cutoff", Timestamp.from(cutoff))
                .param("max", maxStores)
                .query(UUID.class)
                .list();
    }
}
