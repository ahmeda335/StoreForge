package dev.storeforge.developer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.developer.internal.DeveloperProperties;
import dev.storeforge.developer.internal.ratelimit.ApiRateLimiter;
import dev.storeforge.developer.internal.ratelimit.UsageCounters;
import dev.storeforge.shared.redis.RedisConnections;
import dev.storeforge.shared.redis.RedisProperties;
import dev.storeforge.support.DeveloperFlows;
import dev.storeforge.support.DeveloperFlows.Key;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.RedisTestServer;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Rate limits live in Redis (shared by all instances); without Redis the API fails OPEN to local limits. */
class RateLimitIT extends IntegrationTest {

    @Autowired
    DeveloperProperties limits;

    @Autowired
    RedisProperties redisSettings;

    @Autowired
    CircuitBreaker redisCircuitBreaker;

    @Autowired
    UsageCounters usage;

    @Autowired
    RedisConnections redis;

    Owner shop;
    Key key;

    @BeforeEach
    void shopWithKey() throws InterruptedException {
        // Isolation: a previous test may have frozen Redis; start from a responsive Redis and a closed breaker.
        awaitRedis();
        redisCircuitBreaker.reset();
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        key = new DeveloperFlows(api).createKey(shop, "read_orders");
    }

    @Test
    void aBurstBeyondTheBucketGets429WithRetryAfterAndIsCounted() throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            responses.add(orders(key));
        }

        assertThat(responses.subList(0, 38)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        HttpResponse<String> limited = responses.getLast();
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(Json.parse(limited.body()).get("code").asString()).isEqualTo("rate_limited");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.headers().firstValue("X-RateLimit-Remaining")).hasValue("0");
        long rejected = responses.stream().filter(r -> r.statusCode() == 429).count();

        JsonNode hour = awaitUsage(45);
        assertThat(hour.get("rateLimited").asLong()).isEqualTo(rejected);
    }

    /** Another instance (a second limiter on the same Redis) spends the budget; THIS instance sees it. */
    @Test
    void theBudgetIsSharedAcrossInstancesThroughRedis() {
        try (RedisConnections otherInstance = new RedisConnections(redisSettings, Clock.systemUTC())) {
            ApiRateLimiter limiter = new ApiRateLimiter(otherInstance, CircuitBreaker.ofDefaults("other"), limits, new SimpleMeterRegistry());
            drain(() -> limiter.tryConsume(shop.storeId(), key.id()));
        }

        assertThat(orders(key).statusCode()).as("budget already spent elsewhere").isEqualTo(429);
    }

    /** One store's integrations together can't exceed the store budget, even with fresh keys. */
    @Test
    void theStoreWideBudgetCapsAllKeysTogether() {
        Key fresh = new DeveloperFlows(api).createKey(shop, "read_orders");   // first: the bucket refills 5/s meanwhile
        try (RedisConnections otherInstance = new RedisConnections(redisSettings, Clock.systemUTC())) {
            ApiRateLimiter limiter = new ApiRateLimiter(otherInstance, CircuitBreaker.ofDefaults("other"), limits, new SimpleMeterRegistry());
            drain(() -> limiter.tryConsume(shop.storeId(), UUID.randomUUID()));   // many different keys, 1 request each
        }

        assertThat(orders(fresh).statusCode()).as("its own bucket is full, the store's is empty").isEqualTo(429);
    }

    /**
     * Redis frozen (container paused: connections stay open, nothing answers). The API keeps
     * serving, limits fall back to per-instance buckets, and once the breaker opens no request
     * waits for Redis's timeout any more.
     */
    @Test
    void withoutRedisTheApiFailsOpenToLocalLimitsAndStopsWaiting() throws InterruptedException {
        redisCircuitBreaker.reset();
        RedisTestServer.pause();
        List<HttpResponse<String>> responses = new ArrayList<>();
        List<Long> millis = new ArrayList<>();
        try {
            for (int i = 0; i < 60; i++) {
                long started = System.nanoTime();
                responses.add(orders(key));
                millis.add((System.nanoTime() - started) / 1_000_000);
            }
            // EXP-014 regression: an optional dependency down must not make the whole app look dead.
            HttpResponse<String> health = management.get("/actuator/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(Json.parse(health.body()).get("status").asString()).isEqualTo("DEGRADED");
            assertThat(management.get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        } finally {
            RedisTestServer.unpause();
        }

        assertThat(responses.subList(0, 38)).as("still serving").allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        // The first ~5 requests each waited for Redis's timeout before the breaker opened; the local bucket
        // refilled meanwhile (2/s), so "allowed" is 40 plus a little, not exactly 40.
        long allowed = responses.stream().filter(r -> r.statusCode() == 200).count();
        assertThat(allowed).as("still limited, per instance").isBetween(40L, 50L);
        assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(millis.subList(20, 60)).as("no request waits for Redis once the breaker is open").allMatch(ms -> ms < 200);
        assertThat(management.get("/actuator/prometheus").body()).containsPattern("storeforge_ratelimit_decisions_total\\{application=\"storeforge\",backend=\"local\",limit=\"developer-api\"");

        // Recovery without a restart: Redis answers again, the breaker closes, budgets are shared again.
        awaitRedis();
        redisCircuitBreaker.transitionToHalfOpenState();   // what its 10 s wait would do
        Key fresh = new DeveloperFlows(api).createKey(shop, "read_orders");
        for (int i = 0; i < 5; i++) {
            orders(fresh);
        }
        assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private void awaitRedis() throws InterruptedException {
        for (int i = 0; i < 50 && !redis.ping(); i++) {
            Thread.sleep(100);
        }
        assertThat(redis.ping()).as("Redis answers").isTrue();
    }

    /**
     * Consume until refused. Not "N times": draining takes a few hundred ms of Redis round trips and
     * the bucket refills meanwhile, so a fixed count leaves it non-empty some of the time (flaky).
     */
    private static void drain(java.util.function.Supplier<ApiRateLimiter.Decision> consume) {
        for (int i = 0; i < 500 && consume.get().allowed(); i++) {
            // keep consuming
        }
    }

    private HttpResponse<String> orders(Key credential) {
        return api.get(DeveloperFlows.path(shop.storeId(), "/orders"), credential.auth());
    }

    /** Increments are asynchronous: roll up until they're all there. */
    private JsonNode awaitUsage(int requests) throws InterruptedException {
        JsonNode hour = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            usage.rollUp(Instant.now());
            JsonNode data = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/api-keys/" + key.id() + "/usage", shop.auth()).body())
                    .get("data");
            hour = data.isEmpty() ? null : data.get(0);
            if (hour != null && hour.get("requests").asInt() == requests) {
                return hour;
            }
            Thread.sleep(100);
        }
        assertThat(hour).as("usage rolled up").isNotNull();
        assertThat(hour.get("requests").asInt()).isEqualTo(requests);
        return hour;
    }
}
