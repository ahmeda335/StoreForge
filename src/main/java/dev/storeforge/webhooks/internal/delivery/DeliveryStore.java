package dev.storeforge.webhooks.internal.delivery;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Deliveries and their attempts (tenant data, RLS). Every method runs inside the caller's tenant transaction. */
@Repository
@RequiredArgsConstructor
public class DeliveryStore {

    public static final List<String> REPLAYABLE = List.of("SUCCEEDED", "DEAD", "PAUSED", "DISCARDED");

    private static final String COLUMNS = """
            id, store_id, subscription_id, event_id, topic, payload, status, attempts, replays, next_attempt_at,
            last_status_code, last_error, delivered_at, created_at, updated_at""";

    private final JdbcClient jdbc;

    public record Delivery(UUID id, UUID storeId, UUID subscriptionId, UUID eventId, String topic, String payload, String status,
                           int attempts, int replays, @Nullable Instant nextAttemptAt, @Nullable Integer lastStatusCode,
                           @Nullable String lastError, @Nullable Instant deliveredAt, Instant createdAt, Instant updatedAt) {
    }

    public record Attempt(int attemptNumber, Instant requestedAt, int durationMs, @Nullable Integer statusCode, String outcome,
                          @Nullable String error, @Nullable String responseExcerpt) {
    }

    /** @return the new delivery's id, or empty if this subscription already has a delivery for the event */
    public Optional<UUID> insert(StoreId storeId, UUID subscriptionId, UUID eventId, String topic, String payload, String status,
                                 Instant now) {
        return jdbc.sql("""
                INSERT INTO webhook_deliveries (id, store_id, subscription_id, event_id, topic, payload, status, created_at, updated_at)
                VALUES (:id, :storeId, :subscriptionId, :eventId, :topic, :payload, :status, :now, :now)
                ON CONFLICT (subscription_id, event_id) DO NOTHING
                RETURNING id
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("subscriptionId", subscriptionId)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("payload", payload)
                .param("status", status)
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .optional();
    }

    public Optional<Delivery> find(StoreId storeId, UUID id, boolean lock) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_deliveries WHERE store_id = :storeId AND id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("storeId", storeId.value())
                .param("id", id)
                .query(DeliveryStore::map)
                .optional();
    }

    public List<Delivery> page(StoreId storeId, UUID subscriptionId, @Nullable String status, int limitPlusOne, @Nullable Cursor after) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM webhook_deliveries WHERE store_id = :storeId AND subscription_id = :subscriptionId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId.value());
        params.put("subscriptionId", subscriptionId);
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        if (after != null) {
            sql.append(" AND (created_at, id) < (:afterTime, :afterId)");
            params.put("afterTime", Timestamp.from(after.timestamp()));
            params.put("afterId", after.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        params.put("limit", limitPlusOne);
        return jdbc.sql(sql.toString()).params(params).query(DeliveryStore::map).list();
    }

    public List<Attempt> attempts(StoreId storeId, UUID deliveryId) {
        return jdbc.sql("""
                SELECT attempt_number, requested_at, duration_ms, status_code, outcome, error, response_excerpt
                  FROM webhook_delivery_attempts WHERE store_id = :storeId AND delivery_id = :deliveryId ORDER BY attempt_number
                """)
                .param("storeId", storeId.value())
                .param("deliveryId", deliveryId)
                .query((rs, rowNum) -> new Attempt(rs.getInt("attempt_number"), rs.getTimestamp("requested_at").toInstant(),
                        rs.getInt("duration_ms"), rs.getObject("status_code", Integer.class), rs.getString("outcome"),
                        rs.getString("error"), rs.getString("response_excerpt")))
                .list();
    }

    public void insertAttempt(StoreId storeId, UUID deliveryId, Instant requestedAt, long durationMs, @Nullable Integer statusCode,
                              boolean succeeded, @Nullable String error, @Nullable String excerpt) {
        jdbc.sql("""
                INSERT INTO webhook_delivery_attempts (id, store_id, delivery_id, attempt_number, requested_at, duration_ms, status_code,
                                                       outcome, error, response_excerpt)
                VALUES (:id, :storeId, :deliveryId,
                        (SELECT coalesce(max(attempt_number), 0) + 1 FROM webhook_delivery_attempts WHERE delivery_id = :deliveryId),
                        :requestedAt, :duration, :statusCode, :outcome, :error, :excerpt)
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("deliveryId", deliveryId)
                .param("requestedAt", Timestamp.from(requestedAt))
                .param("duration", (int) Math.min(durationMs, Integer.MAX_VALUE))
                .param("statusCode", statusCode)
                .param("outcome", succeeded ? "SUCCEEDED" : "FAILED")
                .param("error", error)
                .param("excerpt", excerpt)
                .update();
    }

    /** Records the outcome of an attempt: new status, attempts + 1, next attempt time (if retrying). */
    public void recordAttempt(StoreId storeId, UUID id, String status, @Nullable Instant nextAttemptAt, @Nullable Integer statusCode,
                              @Nullable String error, Instant now) {
        jdbc.sql("""
                UPDATE webhook_deliveries
                   SET status = :status, attempts = attempts + 1, next_attempt_at = :next, last_status_code = :statusCode,
                       last_error = :error, delivered_at = CASE WHEN :status = 'SUCCEEDED' THEN :now ELSE delivered_at END,
                       updated_at = :now
                 WHERE store_id = :storeId AND id = :id
                """)
                .param("status", status)
                .param("next", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
                .param("statusCode", statusCode)
                .param("error", error)
                .param("now", Timestamp.from(now))
                .param("storeId", storeId.value())
                .param("id", id)
                .update();
    }

    /** Stops a delivery without an attempt (subscription paused or disabled). */
    public void park(StoreId storeId, UUID id, String status, Instant now) {
        jdbc.sql("UPDATE webhook_deliveries SET status = :status, next_attempt_at = NULL, updated_at = :now WHERE store_id = :storeId AND id = :id")
                .param("status", status).param("now", Timestamp.from(now)).param("storeId", storeId.value()).param("id", id).update();
    }

    /**
     * Starts a new delivery cycle for a finished delivery (same event id, so receivers that already
     * processed it dedup it). @return the new replay number, or empty if it is still in flight
     */
    public Optional<Integer> replay(StoreId storeId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE webhook_deliveries
                   SET status = 'PENDING', attempts = 0, replays = replays + 1, next_attempt_at = NULL, updated_at = :now
                 WHERE store_id = :storeId AND id = :id AND status IN ('SUCCEEDED', 'DEAD', 'PAUSED', 'DISCARDED')
                RETURNING replays
                """)
                .param("now", Timestamp.from(now)).param("storeId", storeId.value()).param("id", id)
                .query(Integer.class).optional();
    }

    public List<UUID> replayable(StoreId storeId, UUID subscriptionId, Collection<String> statuses, Instant since, int max) {
        return jdbc.sql("""
                SELECT id FROM webhook_deliveries
                 WHERE store_id = :storeId AND subscription_id = :subscriptionId AND status IN (:statuses) AND created_at >= :since
                 ORDER BY created_at LIMIT :max
                """)
                .param("storeId", storeId.value())
                .param("subscriptionId", subscriptionId)
                .param("statuses", statuses)
                .param("since", Timestamp.from(since))
                .param("max", max)
                .query(UUID.class)
                .list();
    }

    public int purge(Instant cutoff) {
        return jdbc.sql("SELECT purge_webhook_deliveries(:cutoff)").param("cutoff", Timestamp.from(cutoff)).query(Integer.class).single();
    }

    private static Delivery map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp next = rs.getTimestamp("next_attempt_at");
        Timestamp delivered = rs.getTimestamp("delivered_at");
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class), rs.getObject("subscription_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getString("topic"), rs.getString("payload"), rs.getString("status"),
                rs.getInt("attempts"), rs.getInt("replays"), next == null ? null : next.toInstant(),
                rs.getObject("last_status_code", Integer.class), rs.getString("last_error"),
                delivered == null ? null : delivered.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
