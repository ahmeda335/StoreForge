package dev.storeforge.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import dev.storeforge.support.StorefrontFlows.Cart;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Duplicate requests are normal (double clicks, client retries after timeouts). They must never complete twice. */
class CheckoutIdempotencyIT extends IntegrationTest {

    Owner shop;
    StorefrontFlows storefront;
    UUID variant;

    @BeforeEach
    void shopWithStock() {
        CatalogFlows catalog = new CatalogFlows(api);
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        storefront = new StorefrontFlows(api, shop);
        variant = catalog.createVariant(shop, "Lamp");   // 10.00
        catalog.adjustStock(shop, variant, 10, "RECEIVED");
    }

    @Test
    void theKeyIsRequired() {
        Cart cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 1, uniqueEmail());

        HttpResponse<String> response = storefront.complete(cart, checkoutId, "10.00", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.parse(response.body()).get("code").asString()).isEqualTo("idempotency_key_required");
    }

    @Test
    void twentyConcurrentRetriesWithOneKeyCompleteOnceAndAllGetTheSameAnswer() throws Exception {
        Cart cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 3, uniqueEmail());
        String key = UUID.randomUUID().toString();

        List<HttpResponse<String>> responses = race(20, i -> storefront.complete(cart, checkoutId, "30.00", key));

        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).as(response.body()).isEqualTo(200));
        assertThat(responses.stream().map(HttpResponse::body).distinct()).as("identical bodies").hasSize(1);
        assertThat(responses.stream().filter(r -> r.headers().firstValue("Idempotent-Replayed").orElse("").equals("false")))
                .as("exactly one request did the work").hasSize(1);
        assertThat(reserved()).as("stock reserved once, not 20 times").isEqualTo(3);
    }

    @Test
    void differentKeysForTheSameCheckoutStillCompleteOnlyOnce() throws Exception {
        Cart cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 2, uniqueEmail());

        List<HttpResponse<String>> responses = race(10, i -> storefront.complete(cart, checkoutId, "20.00", UUID.randomUUID().toString()));

        assertThat(responses).filteredOn(r -> r.statusCode() == 200).hasSize(1);
        assertThat(responses).filteredOn(r -> r.statusCode() != 200)
                .allSatisfy(r -> assertThat(Json.parse(r.body()).get("code").asString()).isEqualTo("checkout_not_open"));
        assertThat(reserved()).isEqualTo(2);
    }

    @Test
    void aKeyCannotBeReusedForADifferentRequestOrByAnotherShopper() {
        Cart cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 1, uniqueEmail());
        String key = UUID.randomUUID().toString();
        String firstBody = storefront.complete(cart, checkoutId, "10.00", key).body();

        HttpResponse<String> differentPayload = storefront.complete(cart, checkoutId, "11.00", key);
        HttpResponse<String> otherShopper = storefront.complete(storefront.newCart().token(), checkoutId, "10.00", key);
        HttpResponse<String> genuineRetry = storefront.complete(cart, checkoutId, "10.00", key);

        assertThat(Json.parse(differentPayload.body()).get("code").asString()).isEqualTo("idempotency_key_reused");
        assertThat(otherShopper.statusCode()).isEqualTo(422);
        assertThat(otherShopper.body()).as("no replay of another shopper's PII").doesNotContain("@example.test");
        assertThat(genuineRetry.body()).isEqualTo(firstBody);
        assertThat(genuineRetry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
    }

    @Test
    void failedAttemptsAreNotStoredSoARetryCanSucceed() {
        Cart cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 11, uniqueEmail());   // more than in stock
        String key = UUID.randomUUID().toString();

        assertThat(storefront.complete(cart, checkoutId, "110.00", key).statusCode()).isEqualTo(422);
        api.post("/admin/v1/stores/" + shop.storeId() + "/variants/" + variant + "/inventory/adjustments",
                "{\"delta\":5,\"reason\":\"RECEIVED\"}", shop.auth());

        assertThat(storefront.complete(cart, checkoutId, "110.00", key).statusCode()).isEqualTo(200);
    }

    private List<HttpResponse<String>> race(int requests, IntFunction<HttpResponse<String>> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.apply(index);
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }

    private int reserved() {
        return Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/variants/" + variant + "/inventory", shop.auth()).body())
                .get("reserved").asInt();
    }
}
