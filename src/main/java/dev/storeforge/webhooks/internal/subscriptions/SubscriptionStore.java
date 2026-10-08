package dev.storeforge.webhooks.internal.subscriptions;

import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Webhook subscriptions (tenant data, RLS). Every method runs inside the caller's tenant transaction. */
@Repository
@RequiredArgsConstructor
public class SubscriptionStore {

    private static final String COLUMNS = """
            id, store_id, url, topics, api_version, status, secret_ciphertext, previous_secret_ciphertext,
            previous_secret_expires_at, consecutive_failures, last_success_at, last_failure_at, paused_at, created_by,
            created_at, updated_at""";

    private final JdbcClient jdbc;

    public record Subscription(UUID id, UUID storeId, String url, List<String> topics, String apiVersion, String status,
                               String secretCiphertext, @Nullable String previousSecretCiphertext,
                               @Nullable Instant previousSecretExpiresAt, int consecutiveFailures, @Nullable Instant lastSuccessAt,
                               @Nullable Instant lastFailureAt, @Nullable Instant pausedAt, @Nullable UUID createdBy,
                               Instant createdAt, Instant updatedAt) {
    }

    /**
     * Serializes subscription creation per store for the quota check: "count, then insert" by two
     * concurrent requests would otherwise both see 19 of 20 (write skew, EXP-003). A transaction-
     * scoped advisory lock is enough: nothing else needs to block on it.
     */
    public void lockStore(StoreId storeId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('webhook-subscriptions:' || :storeId, 0))")
                .param("storeId", storeId.toString()).query(Object.class).single();
    }

    public int count(StoreId storeId) {
        return jdbc.sql("SELECT count(*) FROM webhook_subscriptions WHERE store_id = :storeId")
                .param("storeId", storeId.value()).query(Integer.class).single();
    }

    public void insert(StoreId storeId, UUID id, String url, List<String> topics, String apiVersion, String secretCiphertext,
                       UUID createdBy, Instant now) {
        jdbc.sql("""
                INSERT INTO webhook_subscriptions (id, store_id, url, topics, api_version, status, secret_ciphertext, created_by,
                                                   created_at, updated_at)
                VALUES (:id, :storeId, :url, CAST(:topics AS text[]), :apiVersion, 'ACTIVE', :secret, :createdBy, :now, :now)
                """)
                .param("id", id)
                .param("storeId", storeId.value())
                .param("url", url)
                .param("topics", topics.toArray(String[]::new))
                .param("apiVersion", apiVersion)
                .param("secret", secretCiphertext)
                .param("createdBy", createdBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    public Optional<Subscription> find(StoreId storeId, UUID id, boolean lock) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_subscriptions WHERE store_id = :storeId AND id = :id"
                        + (lock ? " FOR UPDATE" : ""))
                .param("storeId", storeId.value())
                .param("id", id)
                .query(SubscriptionStore::map)
                .optional();
    }

    public List<Subscription> list(StoreId storeId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_subscriptions WHERE store_id = :storeId ORDER BY created_at, id")
                .param("storeId", storeId.value())
                .query(SubscriptionStore::map)
                .list();
    }

    /** Everyone listening to the topic, except subscriptions the merchant disabled. */
    public List<Subscription> subscribedTo(StoreId storeId, String topic) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_subscriptions"
                        + " WHERE store_id = :storeId AND :topic = ANY (topics) AND status <> 'DISABLED' ORDER BY id")
                .param("storeId", storeId.value())
                .param("topic", topic)
                .query(SubscriptionStore::map)
                .list();
    }

    public void update(StoreId storeId, UUID id, String url, List<String> topics, String status, boolean resetFailures, Instant now) {
        jdbc.sql("""
                UPDATE webhook_subscriptions
                   SET url = :url, topics = CAST(:topics AS text[]), status = :status, updated_at = :now,
                       consecutive_failures = CASE WHEN :reset THEN 0 ELSE consecutive_failures END,
                       paused_at = CASE WHEN :status = 'PAUSED_FAILING' THEN paused_at END
                 WHERE store_id = :storeId AND id = :id
                """)
                .param("url", url)
                .param("topics", topics.toArray(String[]::new))
                .param("status", status)
                .param("reset", resetFailures)
                .param("now", Timestamp.from(now))
                .param("storeId", storeId.value())
                .param("id", id)
                .update();
    }

    public void rotate(StoreId storeId, UUID id, String newCiphertext, String previousCiphertext, Instant previousExpiresAt, Instant now) {
        jdbc.sql("""
                UPDATE webhook_subscriptions
                   SET secret_ciphertext = :secret, previous_secret_ciphertext = :previous, previous_secret_expires_at = :expires,
                       updated_at = :now
                 WHERE store_id = :storeId AND id = :id
                """)
                .param("secret", newCiphertext)
                .param("previous", previousCiphertext)
                .param("expires", Timestamp.from(previousExpiresAt))
                .param("now", Timestamp.from(now))
                .param("storeId", storeId.value())
                .param("id", id)
                .update();
    }

    public boolean delete(StoreId storeId, UUID id) {
        return jdbc.sql("DELETE FROM webhook_subscriptions WHERE store_id = :storeId AND id = :id")
                .param("storeId", storeId.value()).param("id", id).update() == 1;
    }

    public void recordSuccess(StoreId storeId, UUID id, Instant now) {
        jdbc.sql("""
                UPDATE webhook_subscriptions SET consecutive_failures = 0, last_success_at = :now
                 WHERE store_id = :storeId AND id = :id
                """)
                .param("now", Timestamp.from(now)).param("storeId", storeId.value()).param("id", id).update();
    }

    /** @return consecutive failures after this one (atomic increment: concurrent deliveries can't lose counts) */
    public int recordFailure(StoreId storeId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE webhook_subscriptions SET consecutive_failures = consecutive_failures + 1, last_failure_at = :now
                 WHERE store_id = :storeId AND id = :id
                RETURNING consecutive_failures
                """)
                .param("now", Timestamp.from(now)).param("storeId", storeId.value()).param("id", id)
                .query(Integer.class).optional().orElse(0);
    }

    /** @return true for exactly one caller: the one that paused it (notifications are sent once) */
    public boolean pause(StoreId storeId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE webhook_subscriptions SET status = 'PAUSED_FAILING', paused_at = :now, updated_at = :now
                 WHERE store_id = :storeId AND id = :id AND status = 'ACTIVE'
                """)
                .param("now", Timestamp.from(now)).param("storeId", storeId.value()).param("id", id).update() == 1;
    }

    private static Subscription map(ResultSet rs, int rowNum) throws SQLException {
        Array topics = rs.getArray("topics");
        return new Subscription(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class), rs.getString("url"),
                Arrays.asList((String[]) topics.getArray()), rs.getString("api_version"), rs.getString("status"),
                rs.getString("secret_ciphertext"), rs.getString("previous_secret_ciphertext"),
                instant(rs, "previous_secret_expires_at"), rs.getInt("consecutive_failures"), instant(rs, "last_success_at"),
                instant(rs, "last_failure_at"), instant(rs, "paused_at"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
