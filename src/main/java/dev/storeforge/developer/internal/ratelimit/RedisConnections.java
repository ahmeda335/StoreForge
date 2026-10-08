package dev.storeforge.developer.internal.ratelimit;

import dev.storeforge.developer.internal.DeveloperProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Lazily opened Lettuce connections. Redis is an OPTIONAL dependency (ADR-020): the application
 * starts and serves without it, so connecting is attempted on use, not at startup, and a failed
 * attempt is not retried for {@code reconnectBackoff}.
 *
 * <p>Two client options matter: commands time out quickly ({@code commandTimeout}, default 250 ms),
 * and while the connection is down commands are REJECTED immediately instead of being queued until
 * Redis comes back (Lettuce's default, which turns an outage into hanging requests).
 */
@Slf4j
public class RedisConnections implements AutoCloseable {

    private final RedisClient client;
    private final DeveloperProperties.Redis settings;
    private final Clock clock;
    private volatile @Nullable StatefulRedisConnection<byte[], byte[]> bytes;
    private volatile @Nullable StatefulRedisConnection<String, String> strings;
    private volatile Instant nextAttempt = Instant.EPOCH;

    public RedisConnections(DeveloperProperties.Redis settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.client = RedisClient.create(settings.url());
        this.client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(settings.commandTimeout()))
                .build());
    }

    public StatefulRedisConnection<byte[], byte[]> bytes() {
        StatefulRedisConnection<byte[], byte[]> connection = bytes;
        if (connection == null) {
            synchronized (this) {
                if (bytes == null) {
                    bytes = connect(ByteArrayCodec.INSTANCE);
                }
                connection = bytes;
            }
        }
        return connection;
    }

    public StatefulRedisConnection<String, String> strings() {
        StatefulRedisConnection<String, String> connection = strings;
        if (connection == null) {
            synchronized (this) {
                if (strings == null) {
                    strings = connect(StringCodec.UTF8);
                }
                connection = strings;
            }
        }
        return connection;
    }

    private <K, V> StatefulRedisConnection<K, V> connect(io.lettuce.core.codec.RedisCodec<K, V> codec) {
        if (clock.instant().isBefore(nextAttempt)) {
            throw new RedisUnavailableException("Redis connection failed recently; not retrying yet");
        }
        try {
            StatefulRedisConnection<K, V> connection = client.connect(codec);
            connection.setTimeout(settings.commandTimeout());
            log.info("Connected to Redis");
            return connection;
        } catch (RuntimeException e) {
            nextAttempt = clock.instant().plus(settings.reconnectBackoff());
            throw new RedisUnavailableException("Redis connection failed: " + e.getMessage());
        }
    }

    public boolean ping() {
        try {
            return "PONG".equals(strings().sync().ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        client.shutdown();
    }

    public static class RedisUnavailableException extends RuntimeException {
        public RedisUnavailableException(String message) {
            super(message);
        }
    }
}
