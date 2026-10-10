package dev.storeforge.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.storeforge.shared.redis.RedisConnections;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Named token-bucket limits shared by all instances through Redis, failing OPEN to per-instance
 * buckets while Redis is unavailable (ADR-020): an outage of a cache makes limits N× looser,
 * never removes them, never blocks requests. Keys are {@code rl:{limit}:{key}}.
 */
@Component
public class DistributedRateLimiter {

    public record Limit(String name, long capacity, Duration period) {
    }

    public record Decision(boolean allowed, long retryAfterSeconds, String backend) {
    }

    private final RedisConnections redis;
    private final CircuitBreaker breaker;
    private final MeterRegistry meterRegistry;
    private final Map<Limit, BucketConfiguration> configurations = new ConcurrentHashMap<>();
    private final Cache<String, io.github.bucket4j.Bucket> localBuckets =
            Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(30)).maximumSize(200_000).build();
    private volatile @Nullable ProxyManager<byte[]> proxies;
    private final java.util.concurrent.locks.ReentrantLock proxiesLock = new java.util.concurrent.locks.ReentrantLock();

    public DistributedRateLimiter(RedisConnections redis, CircuitBreaker redisCircuitBreaker, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.breaker = redisCircuitBreaker;
        this.meterRegistry = meterRegistry;
    }

    public Decision tryConsume(Limit limit, String key) {
        String bucketKey = "rl:" + limit.name() + ":" + key;
        BucketConfiguration configuration = configurations.computeIfAbsent(limit, DistributedRateLimiter::configuration);
        Decision decision;
        try {
            decision = breaker.executeSupplier(() -> decide(proxies().builder().build(bucketKey.getBytes(StandardCharsets.UTF_8), () -> configuration)
                    .tryConsumeAndReturnRemaining(1), "redis"));
        } catch (RuntimeException e) {   // Redis failed, or the breaker is open: decide locally
            decision = decide(localBuckets.get(bucketKey, k -> io.github.bucket4j.Bucket.builder()
                    .addLimit(configuration.getBandwidths()[0]).build()).tryConsumeAndReturnRemaining(1), "local");
        }
        meterRegistry.counter("storeforge.ratelimit.decisions", "limit", limit.name(), "backend", decision.backend(),
                "outcome", decision.allowed() ? "allowed" : "limited").increment();
        return decision;
    }

    /**
     * Gives back a token taken by {@link #tryConsume}: for limits on FAILED attempts, which take a
     * token up front (so parallel attempts can't all pass a check) and return it when the attempt
     * turns out not to be a failure. Best effort: if Redis fails here, the token stays spent.
     */
    public void refund(Limit limit, String key) {
        String bucketKey = "rl:" + limit.name() + ":" + key;
        BucketConfiguration configuration = configurations.computeIfAbsent(limit, DistributedRateLimiter::configuration);
        try {
            breaker.executeRunnable(() -> proxies().builder().build(bucketKey.getBytes(StandardCharsets.UTF_8), () -> configuration).addTokens(1));
        } catch (RuntimeException e) {
            io.github.bucket4j.Bucket local = localBuckets.getIfPresent(bucketKey);
            if (local != null) {
                local.addTokens(1);
            }
        }
    }

    private static Decision decide(ConsumptionProbe probe, String backend) {
        long retryAfter = probe.isConsumed() ? 0 : Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        return new Decision(probe.isConsumed(), retryAfter, backend);
    }

    private static BucketConfiguration configuration(Limit limit) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()).build())
                .build();
    }

    private ProxyManager<byte[]> proxies() {
        ProxyManager<byte[]> current = proxies;
        if (current == null) {
            proxiesLock.lock();   // not synchronized: may connect to Redis (I/O) and would pin a virtual thread
            try {
                if (proxies == null) {
                    proxies = Bucket4jLettuce.casBasedBuilder(redis.bytes())
                            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(30)))
                            .build();
                }
                current = proxies;
            } finally {
                proxiesLock.unlock();
            }
        }
        return current;
    }
}
