package dev.storeforge.shared.redis;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis for shared, losable state (ADR-020): rate limits of the developer API and the storefront,
 * usage counters. Every user fails OPEN; the breaker below lets them all skip Redis at once.
 */
@Configuration(proxyBeanMethods = false)
class RedisConfiguration {

    @Bean(destroyMethod = "close")
    RedisConnections redisConnections(RedisProperties redis, Clock clock) {
        return new RedisConnections(redis, clock);
    }

    /** Opens after repeated Redis failures: rate limiting and usage then skip Redis immediately. */
    @Bean
    CircuitBreaker redisCircuitBreaker(MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry.circuitBreaker("redis");
    }

    /**
     * Redis down is DEGRADED, not DOWN: the app keeps serving (ADR-020). DOWN would turn the overall
     * /actuator/health into 503 and make monitors call a working app dead (found in EXP-014).
     * Not part of readiness either.
     */
    @Bean
    HealthIndicator redisHealthIndicator(RedisConnections redis) {
        return () -> redis.ping() ? Health.up().build()
                : Health.status("DEGRADED").withDetail("impact", "rate limits per instance; usage not recorded").build();
    }
}
