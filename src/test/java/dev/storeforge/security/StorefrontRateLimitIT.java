package dev.storeforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.storeforge.support.HttpTestClient;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Storefront abuse limits with LOW budgets, in their own application context. Each test is a
 * different client (its own X-Forwarded-For, believed because tests connect from the trusted
 * loopback), so tests don't share buckets.
 */
@TestPropertySource(properties = {
        "storeforge.storefront.rate-limits.general.capacity=40",
        "storeforge.storefront.rate-limits.search.capacity=5",
        "storeforge.storefront.rate-limits.discount-attempts.capacity=3",
        "storeforge.storefront.rate-limits.cart-creation.capacity=4"})
class StorefrontRateLimitIT extends IntegrationTest {

    @Test
    void searchIsLimitedPerClientAndOtherClientsAreUnaffected() {
        String client = randomIp();
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            codes.add(search(client).statusCode());
        }
        assertThat(codes.subList(0, 5)).as("5 allowed (no store for this host: 404)").containsOnly(404);
        assertThat(codes.subList(5, 7)).containsOnly(429);
        HttpResponse<String> limited = search(client);
        assertThat(Json.parse(limited.body()).get("code").asString()).isEqualTo("rate_limited");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();

        assertThat(search(randomIp()).statusCode()).as("another client").isEqualTo(404);
    }

    /** Guessing discount codes: 3 attempts per 10 minutes, whatever the checkout. */
    @Test
    void discountCodeGuessingIsCappedPerClient() {
        String client = randomIp();
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            codes.add(api.request("PUT", "/storefront/v1/checkouts/" + java.util.UUID.randomUUID() + "/discount", "{\"code\":\"GUESS" + i + "\"}",
                    headers(client)).statusCode());
        }
        assertThat(codes.subList(0, 3)).doesNotContain(429);
        assertThat(codes.subList(3, 5)).containsOnly(429);
    }

    @Test
    void cartCreationIsCappedAndTheGeneralBudgetCoversEverything() {
        String client = randomIp();
        List<Integer> carts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            carts.add(api.post("/storefront/v1/carts", "", headers(client)).statusCode());
        }
        assertThat(carts.subList(4, 6)).containsOnly(429);

        String browser = randomIp();
        long limited = 0;
        for (int i = 0; i < 45; i++) {
            limited += api.get("/storefront/v1/products", headers(browser)).statusCode() == 429 ? 1 : 0;
        }
        assertThat(limited).as("40 per minute in total").isBetween(4L, 5L);
    }

    /** The P14 finding: a client rotating X-Forwarded-For used to get a fresh budget per request. */
    @Test
    void aClientThatIsNotAProxyCannotRotateItsAddress() {
        Optional<String> lan = HttpEdgeIT.nonLoopbackAddress();
        assumeTrue(lan.isPresent(), "needs a non-loopback IPv4 address to connect from");
        HttpTestClient direct = new HttpTestClient("http://" + lan.get() + ":" + environment.getProperty("local.server.port"));
        long limited = 0;
        for (int i = 0; i < 8; i++) {
            limited += direct.get("/storefront/v1/search?q=lamp", headers(randomIp())).statusCode() == 429 ? 1 : 0;
        }
        assertThat(limited).as("one budget for the real address, whatever the header says").isGreaterThanOrEqualTo(3);
    }

    private HttpResponse<String> search(String client) {
        return api.get("/storefront/v1/search?q=lamp", headers(client));
    }

    private static Map<String, String> headers(String client) {
        return Map.of("X-Forwarded-For", client, "X-Forwarded-Host", "rl-test.storeforge.localhost");
    }

    private static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "198.18." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }
}
