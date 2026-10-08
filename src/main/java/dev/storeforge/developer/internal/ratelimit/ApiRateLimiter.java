package dev.storeforge.developer.internal.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.storeforge.developer.internal.DeveloperProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Token buckets per credential and per store, in REDIS so that every instance enforces the same
 * budget (an in-memory limiter would allow N× the limit with N instances).
 *
 * <p>Redis down (ADR-020): the developer API FAILS OPEN to per-instance in-memory buckets with the
 * same limits. Integrations keep working (limits are N× looser for the duration) instead of the
 * whole API failing because a cache is down. A circuit breaker skips Redis entirely while it is
 * known to be down, so requests don't each wait for a timeout.
 */
@Slf4j
public class ApiRateLimiter {

    public record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds, String backend) {
    }

    private final RedisConnections redis;
    private final CircuitBreaker breaker;
    private final MeterRegistry meterRegistry;
    private final BucketConfiguration credentialConfig;
    private final BucketConfiguration storeConfig;
    private final long credentialCapacity;
    private final Cache<String, Bucket> localBuckets = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).maximumSize(100_000).build();
    private volatile @Nullable ProxyManager<byte[]> proxies;

    public ApiRateLimiter(RedisConnections redis, CircuitBreaker breaker, DeveloperProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.breaker = breaker;
        this.meterRegistry = meterRegistry;
        this.credentialConfig = config(properties.perCredential());
        this.storeConfig = config(properties.perStore());
        this.credentialCapacity = properties.perCredential().capacity();
        breaker.getEventPublisher().onStateTransition(event ->
                log.atWarn().addKeyValue("transition", event.getStateTransition().toString())
                        .log("Redis circuit breaker changed state; rate limits are per instance while it is not CLOSED"));
    }

    public Decision tryConsume(UUID storeId, UUID credentialId) {
        String credentialKey = "rl:cred:" + credentialId;
        String storeKey = "rl:store:" + storeId;
        Decision decision;
        try {
            decision = breaker.executeSupplier(() -> consume(credentialKey, storeKey, this::redisBucket, "redis"));
        } catch (RuntimeException e) {   // includes CallNotPermittedException: circuit open, Redis skipped
            decision = consume(credentialKey, storeKey, this::localBucket, "local");
        }
        meterRegistry.counter("storeforge.ratelimit.decisions", "backend", decision.backend(),
                "outcome", decision.allowed() ? "allowed" : "limited").increment();
        return decision;
    }

    /** Credential budget first; if the STORE budget then refuses, the credential's token is given back. */
    private Decision consume(String credentialKey, String storeKey, Function<String, Bucket> buckets, String backend) {
        Bucket credential = buckets.apply(credentialKey);
        ConsumptionProbe own = credential.tryConsumeAndReturnRemaining(1);
        if (!own.isConsumed()) {
            return new Decision(false, credentialCapacity, 0, seconds(own.getNanosToWaitForRefill()), backend);
        }
        ConsumptionProbe shared = buckets.apply(storeKey).tryConsumeAndReturnRemaining(1);
        if (!shared.isConsumed()) {
            credential.addTokens(1);
            return new Decision(false, credentialCapacity, 0, seconds(shared.getNanosToWaitForRefill()), backend);
        }
        return new Decision(true, credentialCapacity, Math.min(own.getRemainingTokens(), shared.getRemainingTokens()), 0, backend);
    }

    private Bucket redisBucket(String key) {
        return proxies().builder().build(key.getBytes(StandardCharsets.UTF_8),
                () -> key.startsWith("rl:store:") ? storeConfig : credentialConfig);
    }

    private Bucket localBucket(String key) {
        return localBuckets.get(key, k -> Bucket.builder().addLimit(k.startsWith("rl:store:")
                ? storeConfig.getBandwidths()[0] : credentialConfig.getBandwidths()[0]).build());
    }

    private ProxyManager<byte[]> proxies() {
        ProxyManager<byte[]> current = proxies;
        if (current == null) {
            synchronized (this) {
                if (proxies == null) {
                    proxies = Bucket4jLettuce.casBasedBuilder(redis.bytes())
                            // A bucket left alone refills completely; Redis may then forget it.
                            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(30)))
                            .build();
                }
                current = proxies;
            }
        }
        return current;
    }

    public CircuitBreaker.State redisState() {
        return breaker.getState();
    }

    private static BucketConfiguration config(DeveloperProperties.Bucket bucket) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(bucket.capacity()).refillGreedy(bucket.refillPerSecond(), Duration.ofSeconds(1)).build())
                .build();
    }

    private static long seconds(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }
}
