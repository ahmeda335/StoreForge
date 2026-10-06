package dev.storeforge.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.orders.internal.application.UnpaidOrderExpiry;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class OrderLifecycleIT extends IntegrationTest {

    @Autowired
    UnpaidOrderExpiry expiry;

    StoreFlows stores;
    CatalogFlows catalog;
    Owner shop;
    StorefrontFlows storefront;
    UUID variant;

    @BeforeEach
    void shopWithStock() {
        stores = new StoreFlows(api, mailbox);
        catalog = new CatalogFlows(api);
        shop = stores.newOwnerWithStore(uniqueEmail());
        storefront = new StorefrontFlows(api, shop);
        variant = catalog.createVariant(shop, "Chair");   // 10.00
        catalog.adjustStock(shop, variant, 10, "RECEIVED");
    }

    @Test
    void paidThenFulfilledSellsTheStockAndClosesTheOrder() {
        UUID orderId = placeOrder(3);
        assertThat(stock()).as("reserved at checkout").containsExactly(10, 3);

        JsonNode paid = Json.parse(post(orderId, "manual-payment", "{\"note\":\"bank transfer 4711\"}").body());
        assertThat(paid.get("financialStatus").asString()).isEqualTo("PAID");
        assertThat(stock()).as("sold: on hand and reserved both drop").containsExactly(7, 0);

        JsonNode fulfilled = Json.parse(post(orderId, "fulfillment", "").body());
        assertThat(fulfilled.get("status").asString()).isEqualTo("CLOSED");
        assertThat(fulfilled.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");
        assertThat(fulfilled.get("timeline").valueStream().map(e -> e.get("type").asString()))
                .containsExactly("PLACED", "PAID", "FULFILLED");
    }

    @Test
    void cancellingAnUnpaidOrderReleasesItsStock() {
        UUID orderId = placeOrder(4);

        JsonNode cancelled = Json.parse(post(orderId, "cancellation", "{\"reason\":\"customer_request\"}").body());

        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("financialStatus").asString()).isEqualTo("VOIDED");
        assertThat(stock()).containsExactly(10, 0);
        var audit = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/audit-log?limit=5", shop.auth()).body()).get("data");
        assertThat(audit.get(0).get("action").asString()).isEqualTo("order.cancelled");
    }

    @Test
    void illegalTransitionsAreConflicts() {
        UUID unpaid = placeOrder(1);
        assertThat(code(post(unpaid, "fulfillment", ""))).isEqualTo("order_not_paid");

        UUID paid = placeOrder(1);
        post(paid, "manual-payment", "{}");
        assertThat(code(post(paid, "manual-payment", "{}"))).isEqualTo("order_already_paid");
        HttpResponse<String> cancelPaid = post(paid, "cancellation", "{\"reason\":\"other\"}");
        assertThat(cancelPaid.statusCode()).isEqualTo(409);
        assertThat(code(cancelPaid)).isEqualTo("cancel_requires_refund");

        post(unpaid, "cancellation", "{\"reason\":\"other\"}");
        assertThat(code(post(unpaid, "manual-payment", "{}"))).isEqualTo("order_not_open");
    }

    /** "Pay" and "cancel" race on one order: exactly one wins and stock ends up consistent with the winner. */
    @Test
    void concurrentPayAndCancelNeverBothSucceed() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID orderId = placeOrder(1);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> results = new ArrayList<>();
            try (var executor = Executors.newFixedThreadPool(2)) {
                results.add(executor.submit(() -> { start.await(); return post(orderId, "manual-payment", "{}"); }));
                results.add(executor.submit(() -> { start.await(); return post(orderId, "cancellation", "{\"reason\":\"other\"}"); }));
                start.countDown();
                int paidStatus = results.get(0).get().statusCode();
                int cancelStatus = results.get(1).get().statusCode();
                assertThat(List.of(paidStatus, cancelStatus)).containsExactlyInAnyOrder(200, 409);
            }
        }
        // 5 rounds of 1 unit: whatever won, nothing may stay reserved and on-hand = 10 - (number of paid orders).
        int[] level = stock();
        assertThat(level[1]).isZero();
        long paidOrders = Json.parse(api.get(orders() + "?financialStatus=PAID", shop.auth()).body()).get("data").size();
        assertThat(level[0]).isEqualTo(10 - (int) paidOrders);
    }

    @Test
    void unpaidOrdersExpireAfterThePaymentWindowAndReleaseStock() {
        UUID overdue = placeOrder(2);
        UUID paid = placeOrder(1);
        post(paid, "manual-payment", "{}");

        assertThat(expiry.expireOverdue(Instant.now())).as("nothing overdue yet").isZero();
        int expired = expiry.expireOverdue(Instant.now().plusSeconds(16 * 60));

        // The job is platform-wide: unpaid orders of other tests' stores (same database) expire too.
        assertThat(expired).isGreaterThanOrEqualTo(1);
        JsonNode order = Json.parse(api.get(orders() + "/" + overdue, shop.auth()).body());
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("cancelReason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(order.get("timeline").valueStream().map(e -> e.get("type").asString())).containsExactly("PLACED", "EXPIRED");
        assertThat(stock()).as("paid unit sold, expired units released").containsExactly(9, 0);
        assertThat(expiry.expireOverdue(Instant.now().plusSeconds(16 * 60))).as("idempotent").isZero();
    }

    @Test
    void listFiltersAndSearch() {
        String email = "findme-" + UUID.randomUUID() + "@example.test";
        UUID first = placeOrder(1, email);
        placeOrder(1);
        post(first, "manual-payment", "{}");

        assertThat(Json.parse(api.get(orders() + "?financialStatus=paid", shop.auth()).body()).get("data").size()).isEqualTo(1);
        var byNumber = Json.parse(api.get(orders() + "?q=%231001", shop.auth()).body()).get("data");
        assertThat(byNumber.get(0).get("name").asString()).isEqualTo("#1001");
        var byEmail = Json.parse(api.get(orders() + "?q=findme-", shop.auth()).body()).get("data");
        assertThat(byEmail.get(0).get("email").asString()).isEqualTo(email);
        assertThat(code(api.get(orders() + "?status=SHIPPED", shop.auth()))).isEqualTo("invalid_parameter");
    }

    @Test
    void readOnlyStaffCannotChangeOrders() {
        UUID viewerRole = stores.createRole(shop, "Order viewer", "orders:read");
        var viewer = StoreFlows.bearer(stores.addMember(shop, uniqueEmail(), viewerRole).accessToken());
        UUID orderId = placeOrder(1);

        assertThat(api.get(orders() + "/" + orderId, viewer).statusCode()).isEqualTo(200);
        assertThat(api.post(orders() + "/" + orderId + "/cancellation", "{\"reason\":\"other\"}", viewer).statusCode()).isEqualTo(403);
    }

    private UUID placeOrder(int quantity) {
        return placeOrder(quantity, uniqueEmail());
    }

    private UUID placeOrder(int quantity, String email) {
        return UUID.fromString(storefront.placeOrder(variant, quantity, email).get("orderId").asString());
    }

    private HttpResponse<String> post(UUID orderId, String action, String body) {
        return api.post(orders() + "/" + orderId + "/" + action, body, shop.auth());
    }

    private int[] stock() {
        var level = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/variants/" + variant + "/inventory", shop.auth()).body());
        return new int[] {level.get("onHand").asInt(), level.get("reserved").asInt()};
    }

    private String orders() {
        return "/admin/v1/stores/" + shop.storeId() + "/orders";
    }

    private static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
