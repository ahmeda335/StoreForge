package dev.storeforge.developer.internal.ratelimit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-credential request statistics. Each request increments a Redis hash
 * {@code usage:<store>:<credential>:<yyyyMMddHH>} (asynchronously, never slowing the request); a
 * scheduled roll-up copies the hashes into {@code app_usage_hourly}. Not Prometheus labels: one
 * series per credential would explode metric cardinality.
 *
 * <p>The roll-up writes ABSOLUTE values (upsert), so running it again, or for a still-open hour, is
 * harmless. If Redis is down, increments are dropped (counted in a metric): usage statistics are
 * the kind of data that may be lossy (ADR-020).
 */
@Slf4j
public class UsageCounters {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final RedisConnections redis;
    private final CircuitBreaker breaker;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Duration retention;
    private final MeterRegistry meterRegistry;

    public UsageCounters(RedisConnections redis, CircuitBreaker breaker, JdbcClient jdbc, TransactionTemplate transaction,
                         Duration retention, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.breaker = breaker;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.retention = retention;
        this.meterRegistry = meterRegistry;
    }

    public void record(UUID storeId, UUID credentialId, int status, boolean rateLimited, Instant now) {
        if (!breaker.tryAcquirePermission()) {
            meterRegistry.counter("storeforge.usage.dropped").increment();
            return;
        }
        breaker.releasePermission();
        try {
            RedisAsyncCommands<String, String> commands = redis.strings().async();
            String key = "usage:" + storeId + ":" + credentialId + ":" + HOUR.format(now);
            commands.hincrby(key, "requests", 1);
            if (rateLimited) {
                commands.hincrby(key, "rate_limited", 1);
            } else if (status >= 500) {
                commands.hincrby(key, "server_errors", 1);
            } else if (status >= 400) {
                commands.hincrby(key, "client_errors", 1);
            }
            commands.expire(key, retention).exceptionally(e -> {
                meterRegistry.counter("storeforge.usage.dropped").increment();
                return false;
            });
        } catch (RuntimeException e) {
            meterRegistry.counter("storeforge.usage.dropped").increment();
        }
    }

    /** Copies the counters of the last few hours (including the current one) into the database. @return rows written */
    public int rollUp(Instant now) {
        RedisCommands<String, String> commands;
        try {
            commands = redis.strings().sync();
        } catch (RuntimeException e) {
            log.atWarn().log("Usage roll-up skipped: Redis unavailable");
            return 0;
        }
        int written = 0;
        for (int back = 0; back < 3; back++) {
            Instant hour = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(back));
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                KeyScanCursor<String> page = commands.scan(cursor, ScanArgs.Builder.matches("usage:*:" + HOUR.format(hour)).limit(500));
                for (String key : page.getKeys()) {
                    written += upsert(key, hour, commands.hgetall(key)) ? 1 : 0;
                }
                cursor = page;
            } while (!cursor.isFinished());
        }
        return written;
    }

    private boolean upsert(String key, Instant hour, Map<String, String> counts) {
        String[] parts = key.split(":");
        UUID storeId = UUID.fromString(parts[1]);
        UUID credentialId = UUID.fromString(parts[2]);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.sql("SELECT set_config('app.current_store_id', :store, true)").param("store", storeId.toString()).query(String.class).single();
            return jdbc.sql("""
                    INSERT INTO app_usage_hourly (store_id, credential_id, hour, requests, client_errors, server_errors, rate_limited)
                    SELECT :store, :credential, :hour, :requests, :client, :server, :limited
                     WHERE EXISTS (SELECT 1 FROM api_credentials WHERE id = :credential)
                    ON CONFLICT (credential_id, hour) DO UPDATE
                       SET requests = EXCLUDED.requests, client_errors = EXCLUDED.client_errors,
                           server_errors = EXCLUDED.server_errors, rate_limited = EXCLUDED.rate_limited
                    """)
                    .param("store", storeId)
                    .param("credential", credentialId)
                    .param("hour", Timestamp.from(hour))
                    .param("requests", count(counts, "requests"))
                    .param("client", count(counts, "client_errors"))
                    .param("server", count(counts, "server_errors"))
                    .param("limited", count(counts, "rate_limited"))
                    .update() == 1;
        }));
    }

    private static int count(Map<String, String> counts, String field) {
        return Integer.parseInt(counts.getOrDefault(field, "0"));
    }
}
