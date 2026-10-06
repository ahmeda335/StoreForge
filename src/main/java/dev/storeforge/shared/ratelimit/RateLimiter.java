package dev.storeforge.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;

/**
 * Per-key token bucket: each key (an IP, an email, later an API client) gets {@code capacity}
 * tokens, refilled continuously at {@code capacity / period}. A request takes one token; an empty
 * bucket means 429. Unlike a fixed window ("10 per minute, reset at :00"), a token bucket has no
 * boundary where a client can fire 2x the limit (end of one window + start of the next).
 *
 * <p>Buckets live in THIS instance's memory, so with N instances the effective limit is N times
 * higher and restarts reset it. That is acceptable for brute-force protection on one instance in
 * Phase 2; P12 moves buckets to Redis so limits are shared across instances.
 *
 * <p>The cache is bounded and expires idle buckets: without that, an attacker rotating keys
 * (random emails, spoofed IPs) would grow memory without limit.
 */
public class RateLimiter {

    private final String name;
    private final Bandwidth bandwidth;
    private final Cache<String, Bucket> buckets;
    private final MeterRegistry meterRegistry;

    public RateLimiter(String name, long capacity, Duration period, MeterRegistry meterRegistry) {
        this.name = name;
        this.bandwidth = Bandwidth.builder().capacity(capacity).refillGreedy(capacity, period).build();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(period.multipliedBy(2))
                .build();
        this.meterRegistry = meterRegistry;
    }

    /** Consumes one token for {@code key} or throws {@link RateLimitExceededException}. */
    public void acquire(String key) {
        Bucket bucket = buckets.get(key, ignored -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            meterRegistry.counter("storeforge.ratelimit.rejected", "limiter", name).increment();
            throw new RateLimitExceededException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }
}
