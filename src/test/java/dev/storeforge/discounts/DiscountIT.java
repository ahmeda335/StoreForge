package dev.storeforge.discounts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import dev.storeforge.support.StorefrontFlows.Cart;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class DiscountIT extends IntegrationTest {

    @Autowired
    JobWorker worker;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    dev.storeforge.shared.tenancy.TenantTransactions tenant;

    @Autowired
    Discounts discounts;

    Owner shop;
    UUID variant;
    StorefrontFlows storefront;

    @BeforeEach
    void shop() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        variant = new CatalogFlows(api).createVariant(shop, "Lamp");   // 10.00
        new CatalogFlows(api).adjustStock(shop, variant, 100, "RECEIVED");
        storefront = new StorefrontFlows(api, shop);
    }

    record Checkout(Cart cart, UUID id) {
    }

    @Test
    void aPercentageCodeLowersTheTotalAndTheOrderRemembersIt() {
        discount("{\"code\":\"SPRING15\",\"kind\":\"PERCENTAGE\",\"value\":\"15\"}");
        Checkout checkout = checkout(3, uniqueEmail());   // 30.00

        JsonNode applied = Json.parse(apply(checkout, "spring15").body());   // codes are case-insensitive
        assertThat(applied.get("discount").get("amount").get("amount").asString()).isEqualTo("4.50");
        assertThat(applied.get("total").get("amount").asString()).isEqualTo("25.50");

        HttpResponse<String> completed = complete(checkout, "25.50");
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        JsonNode order = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/orders/" + Json.parse(completed.body()).get("orderId").asString(),
                shop.auth()).body());
        assertThat(order.get("discount").get("amount").asString()).isEqualTo("4.50");
        assertThat(order.get("discountCode").asString()).isEqualTo("SPRING15");
        assertThat(order.get("total").get("amount").asString()).isEqualTo("25.50");
        assertThat(code(api.get("/admin/v1/stores/" + shop.storeId() + "/discounts", shop.auth()))).isNull();
        assertThat(Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/discounts", shop.auth()).body()).get("data").get(0)
                .get("usageCount").asInt()).isEqualTo(1);
    }

    /**
     * The "last coupon" race: 10 customers complete at the same moment with a single-use code. Each
     * buys a DIFFERENT product: with the same product, the inventory row lock serializes the
     * completions and the coupon never really races (EXP-017: the first version of this test did
     * that, and passed with the usage-limit check removed).
     */
    @Test
    void exactlyOneOfManyConcurrentCheckoutsGetsTheLastUse() throws Exception {
        discount("{\"code\":\"ONLYONE\",\"kind\":\"FIXED_AMOUNT\",\"value\":\"5.00\",\"usageLimit\":1}");
        List<Checkout> checkouts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID own = new CatalogFlows(api).createVariant(shop, "Lamp " + i);
            new CatalogFlows(api).adjustStock(shop, own, 1, "RECEIVED");
            Cart cart = storefront.newCart();
            Checkout checkout = new Checkout(cart, storefront.readyCheckout(cart, own, 1, uniqueEmail()));
            assertThat(apply(checkout, "ONLYONE").statusCode()).isEqualTo(200);
            checkouts.add(checkout);
        }

        List<HttpResponse<String>> responses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(10)) {
            List<Future<HttpResponse<String>>> futures = executor.invokeAll(checkouts.stream()
                    .<Callable<HttpResponse<String>>>map(c -> () -> complete(c, "5.00")).toList());
            for (Future<HttpResponse<String>> f : futures) {
                responses.add(f.get());
            }
        }

        assertThat(responses).filteredOn(r -> r.statusCode() == 200).hasSize(1);
        assertThat(responses).filteredOn(r -> r.statusCode() == 409)
                .hasSize(9)
                .allSatisfy(r -> assertThat(code(r)).isEqualTo("discount_unavailable"));
        JsonNode code = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/discounts", shop.auth()).body()).get("data").get(0);
        assertThat(code.get("usageCount").asInt()).isEqualTo(1);
        // Inside the store's tenant transaction: outside it, RLS would hide every row and the sum would be a vacuous 0.
        int reserved = tenant.inStore(dev.storeforge.shared.tenancy.StoreId.of(shop.storeId()), () -> jdbc.sql(
                "SELECT coalesce(sum(reserved), 0) FROM inventory_items WHERE store_id = :store").param("store", shop.storeId())
                .query(Integer.class).single());
        assertThat(reserved).as("only the winner's reservation; the 9 failed completions rolled theirs back").isEqualTo(1);
    }

    /**
     * The mechanism itself, without the rest of checkout: within one store, completions are already
     * serialized by the order-number counter row (P8), so the end-to-end test above can't tell whether
     * the conditional UPDATE matters (EXP-017). Here 10 redemptions start together, sharing nothing
     * but the code: the conditional UPDATE alone must let exactly one through.
     */
    @Test
    void theConditionalUpdateAloneDecidesTheLastUse() throws Exception {
        discount("{\"code\":\"LAST\",\"kind\":\"FIXED_AMOUNT\",\"value\":\"1.00\",\"usageLimit\":1}");
        dev.storeforge.shared.tenancy.StoreId store = dev.storeforge.shared.tenancy.StoreId.of(shop.storeId());
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(10);
        List<Callable<String>> attempts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String email = uniqueEmail();
            attempts.add(() -> {
                try {
                    return tenant.inStore(store, () -> {
                        try {
                            start.await();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        discounts.redeem(store, "LAST", UUID.randomUUID(), UUID.randomUUID(), 1000, email, Instant.now());
                        return "won";
                    });
                } catch (DiscountUnavailableException e) {
                    return e.reason();
                }
            });
        }
        List<String> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(10)) {
            for (Future<String> f : executor.invokeAll(attempts)) {
                outcomes.add(f.get());
            }
        }

        assertThat(outcomes).filteredOn("won"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("usage_limit_reached"::equals).hasSize(9);
    }

    /** The code expires between showing the total and paying: say so, never charge more silently. */
    @Test
    void aCodeThatExpiresDuringCheckoutStopsTheCompletionWithAClearError() {
        String id = Json.parse(discount("{\"code\":\"FLASH\",\"kind\":\"PERCENTAGE\",\"value\":\"50\",\"startsAt\":\""
                + Instant.now().minusSeconds(3600) + "\"}").body()).get("id").asString();
        Checkout checkout = checkout(2, uniqueEmail());
        assertThat(Json.parse(apply(checkout, "FLASH").body()).get("total").get("amount").asString()).isEqualTo("10.00");

        api.patch("/admin/v1/stores/" + shop.storeId() + "/discounts/" + id, "{\"endsAt\":\"" + Instant.now().minusSeconds(60) + "\"}", shop.auth());

        JsonNode view = Json.parse(storefront.getCheckout(checkout.cart(), checkout.id()).body());
        assertThat(view.get("discount").get("applicable").asBoolean()).isFalse();
        assertThat(view.get("discount").get("reason").asString()).isEqualTo("expired");
        assertThat(view.get("total").get("amount").asString()).as("shown without the discount").isEqualTo("20.00");
        HttpResponse<String> stale = complete(checkout, "10.00");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("discount_unavailable");

        api.delete("/storefront/v1/checkouts/" + checkout.id() + "/discount", storefront.headers(checkout.cart().token()));
        assertThat(complete(checkout, "20.00").statusCode()).isEqualTo(200);
    }

    @Test
    void oncePerCustomerHoldsEvenForParallelCheckoutsOfTheSameCustomer() throws Exception {
        discount("{\"code\":\"WELCOME\",\"kind\":\"FIXED_AMOUNT\",\"value\":\"2.00\",\"oncePerCustomer\":true}");
        String email = uniqueEmail();
        Checkout first = checkout(1, email);
        Checkout second = checkout(1, email);
        apply(first, "WELCOME");
        apply(second, "WELCOME");   // both applied before either completed

        List<HttpResponse<String>> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            results = executor.invokeAll(List.<Callable<HttpResponse<String>>>of(() -> complete(first, "8.00"), () -> complete(second, "8.00")))
                    .stream().map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        }

        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        Checkout third = checkout(1, email);
        HttpResponse<String> again = apply(third, "WELCOME");
        assertThat(again.statusCode()).isEqualTo(422);
        assertThat(Json.parse(again.body()).get("detail").asString()).contains("already_used");
    }

    /**
     * An unpaid order that is cancelled (or expires) gives its use back; otherwise unpaid orders could
     * burn single-use codes. The release must be idempotent: a REDELIVERED event gives back nothing more.
     */
    @Test
    void cancellingAnOrderReturnsItsCodeUseExactlyOnce() {
        discount("{\"code\":\"VIP\",\"kind\":\"PERCENTAGE\",\"value\":\"10\",\"usageLimit\":2}");
        String cancelled = placeWith("VIP");
        placeWith("VIP");
        assertThat(apply(checkout(1, uniqueEmail()), "VIP").statusCode()).as("used up").isEqualTo(422);

        api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + cancelled + "/cancellation", "{\"reason\":\"customer_request\"}", shop.auth());
        worker.drain();
        assertThat(usageCount()).isEqualTo(1);

        int redelivered = jdbc.sql("""
                UPDATE jobs SET status = 'READY', run_at = now() - interval '1 second'
                 WHERE type = 'event:discount-releases' AND event_id IN
                       (SELECT id FROM outbox_events WHERE aggregate_id = :order AND event_type = 'OrderCancelled')
                """).param("order", cancelled).update();
        assertThat(redelivered).isEqualTo(1);
        worker.drain();

        assertThat(usageCount()).as("the other order's use is still taken").isEqualTo(1);
        assertThat(apply(checkout(1, uniqueEmail()), "VIP").statusCode()).isEqualTo(200);
    }

    private String placeWith(String code) {
        Checkout checkout = checkout(1, uniqueEmail());
        apply(checkout, code);
        HttpResponse<String> completed = complete(checkout, "9.00");
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        return Json.parse(completed.body()).get("orderId").asString();
    }

    private int usageCount() {
        return Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/discounts", shop.auth()).body()).get("data").get(0).get("usageCount").asInt();
    }

    @Test
    void rulesAreEnforcedWhenApplying() {
        discount("{\"code\":\"BIG\",\"kind\":\"FIXED_AMOUNT\",\"value\":\"50.00\",\"minimumSubtotal\":\"20.00\"}");
        discount("{\"code\":\"HUGE\",\"kind\":\"FIXED_AMOUNT\",\"value\":\"99.00\"}");
        Checkout small = checkout(1, uniqueEmail());
        assertThat(Json.parse(apply(small, "BIG").body()).get("detail").asString()).contains("minimum_not_met");
        assertThat(Json.parse(apply(small, "NOPE").body()).get("detail").asString()).contains("not_found");
        assertThat(Json.parse(apply(small, "HUGE").body()).get("total").get("amount").asString()).as("capped at the subtotal").isEqualTo("0.00");
        assertThat(code(discount("{\"code\":\"BIG\",\"kind\":\"PERCENTAGE\",\"value\":\"5\"}"))).isEqualTo("discount_code_taken");
        assertThat(discount("{\"code\":\"X\",\"kind\":\"PERCENTAGE\",\"value\":\"5\"}").statusCode()).isEqualTo(422);
        assertThat(code(discount("{\"code\":\"TOOMUCH\",\"kind\":\"PERCENTAGE\",\"value\":\"150\"}"))).isEqualTo("invalid_discount");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private HttpResponse<String> discount(String body) {
        return api.post("/admin/v1/stores/" + shop.storeId() + "/discounts", body, shop.auth());
    }

    private Checkout checkout(int quantity, String email) {
        Cart cart = storefront.newCart();
        return new Checkout(cart, storefront.readyCheckout(cart, variant, quantity, email));
    }

    private HttpResponse<String> apply(Checkout checkout, String code) {
        return api.request("PUT", "/storefront/v1/checkouts/" + checkout.id() + "/discount", "{\"code\":\"" + code + "\"}",
                storefront.headers(checkout.cart().token()));
    }

    private HttpResponse<String> complete(Checkout checkout, String total) {
        return storefront.complete(checkout.cart(), checkout.id(), total, UUID.randomUUID().toString());
    }

    private static String code(HttpResponse<String> response) {
        JsonNode body = Json.parse(response.body());
        return body.has("code") ? body.get("code").asString() : null;
    }
}
