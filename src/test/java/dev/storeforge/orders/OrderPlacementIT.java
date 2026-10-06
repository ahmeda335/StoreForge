package dev.storeforge.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

class OrderPlacementIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantTransactions tenant;

    CatalogFlows catalog;
    Owner shop;
    StorefrontFlows storefront;
    UUID variant;

    @BeforeEach
    void shopWithStock() {
        catalog = new CatalogFlows(api);
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        storefront = new StorefrontFlows(api, shop);
        variant = catalog.createVariant(shop, "Kettle");   // 10.00
        catalog.adjustStock(shop, variant, 100, "RECEIVED");
    }

    @Test
    void completionPlacesAnOrderWithTheCheckoutSnapshot() {
        var completed = storefront.placeOrder(variant, 2, "Grace@Example.test");

        assertThat(completed.get("orderName").asString()).isEqualTo("#1001");
        var order = Json.parse(api.get(orders() + "/" + completed.get("orderId").asString(), shop.auth()).body());
        assertThat(order.get("number").asLong()).isEqualTo(1001);
        assertThat(order.get("email").asString()).isEqualTo("grace@example.test");
        assertThat(order.get("status").asString()).isEqualTo("OPEN");
        assertThat(order.get("financialStatus").asString()).isEqualTo("PENDING");
        assertThat(order.get("total").get("amount").asString()).isEqualTo("20.00");
        assertThat(order.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(order.get("shippingAddress").get("country").asString()).isEqualTo("GB");
        assertThat(order.get("timeline").get(0).get("type").asString()).isEqualTo("PLACED");
        assertThat(order.get("paymentDueAt").asString()).isEqualTo(completed.get("paymentDueAt").asString());
    }

    @Test
    void orderNumbersArePerStore() {
        storefront.placeOrder(variant, 1, uniqueEmail());
        var second = storefront.placeOrder(variant, 1, uniqueEmail());
        Owner otherShop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        UUID otherVariant = catalog.createVariant(otherShop, "Other");
        catalog.adjustStock(otherShop, otherVariant, 5, "RECEIVED");
        var otherFirst = new StorefrontFlows(api, otherShop).placeOrder(otherVariant, 1, uniqueEmail());

        assertThat(second.get("orderName").asString()).isEqualTo("#1002");
        assertThat(otherFirst.get("orderName").asString()).isEqualTo("#1001");
    }

    /** The counter row serializes numbering: concurrent orders get consecutive, unique numbers. */
    @Test
    void concurrentOrdersGetConsecutiveNumbersWithoutGapsOrDuplicates() throws Exception {
        int buyers = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(buyers)) {
            for (int i = 0; i < buyers; i++) {
                results.add(executor.submit(() -> {
                    var cart = storefront.newCart();
                    UUID checkoutId = storefront.readyCheckout(cart, variant, 1, uniqueEmail());
                    start.await();
                    var response = storefront.complete(cart, checkoutId, "10.00", UUID.randomUUID().toString());
                    return Json.parse(response.body()).get("orderName").asString();
                }));
            }
            start.countDown();
            List<String> names = new ArrayList<>();
            for (Future<String> result : results) {
                names.add(result.get());
            }
            assertThat(names).containsExactlyInAnyOrder(
                    java.util.stream.IntStream.rangeClosed(1001, 1000 + buyers).mapToObj(n -> "#" + n).toArray(String[]::new));
        }
    }

    @Test
    void orderLinesAreImmutableForTheApplication() {
        var completed = storefront.placeOrder(variant, 1, uniqueEmail());
        UUID orderId = UUID.fromString(completed.get("orderId").asString());
        StoreId storeId = StoreId.of(shop.storeId());

        assertThatThrownBy(() -> tenant.runInStore(storeId, () -> jdbc.sql("UPDATE order_items SET unit_price_minor = 1 WHERE order_id = :id")
                .param("id", orderId).update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    private String orders() {
        return "/admin/v1/stores/" + shop.storeId() + "/orders";
    }
}
