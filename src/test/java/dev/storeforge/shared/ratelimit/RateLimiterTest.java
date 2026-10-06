package dev.storeforge.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final RateLimiter limiter = new RateLimiter("test", 3, Duration.ofMinutes(1), meters);

    @Test
    void allowsUpToCapacityThenRejectsWithRetryAfter() {
        limiter.acquire("ip-1");
        limiter.acquire("ip-1");
        limiter.acquire("ip-1");

        assertThatThrownBy(() -> limiter.acquire("ip-1"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.code()).isEqualTo("rate_limited");
                    assertThat(Long.parseLong(e.headers().getFirst("Retry-After"))).isBetween(1L, 20L);
                });
        assertThat(meters.counter("storeforge.ratelimit.rejected", "limiter", "test").count()).isEqualTo(1);
    }

    @Test
    void keysHaveIndependentBuckets() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("ip-1");
        }

        limiter.acquire("ip-2");   // does not throw
    }
}
