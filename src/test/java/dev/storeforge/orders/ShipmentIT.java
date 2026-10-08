package dev.storeforge.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Partial fulfilment: shipments with tracking, never more than ordered, cancellable while in transit. */
class ShipmentIT extends IntegrationTest {

    Owner shop;
    UUID lamp;
    UUID shade;
    String orderId;

    @BeforeEach
    void paidOrderOfThreeLampsAndTwoShades() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        CatalogFlows catalog = new CatalogFlows(api);
        lamp = catalog.createVariant(shop, "Lamp");
        shade = catalog.createVariant(shop, "Shade");
        catalog.adjustStock(shop, lamp, 10, "RECEIVED");
        catalog.adjustStock(shop, shade, 10, "RECEIVED");
        StorefrontFlows storefront = new StorefrontFlows(api, shop);
        var cart = storefront.newCart();
        storefront.addItem(cart, lamp, 3);
        UUID checkoutId = storefront.readyCheckout(cart, shade, 2, uniqueEmail());
        orderId = Json.parse(storefront.complete(cart, checkoutId, "50.00", UUID.randomUUID().toString()).body()).get("orderId").asString();
        api.post(orderPath("/manual-payment"), "{}", shop.auth());
    }

    @Test
    void anOrderShipsInPartsAndClosesWhenEverythingHasShipped() {
        HttpResponse<String> first = ship("{\"lines\":[{\"variantId\":\"" + lamp + "\",\"quantity\":2}],\"carrier\":\"DHL\",\"trackingNumber\":\"JD0001\"}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        JsonNode order = order();
        assertThat(order.get("fulfillmentStatus").asString()).isEqualTo("PARTIALLY_FULFILLED");
        assertThat(order.get("status").asString()).isEqualTo("OPEN");

        HttpResponse<String> rest = ship("{\"trackingNumber\":\"JD0002\"}");   // no lines: everything remaining
        assertThat(Json.parse(rest.body()).get("items").get(lamp.toString()).asInt()).isEqualTo(1);
        assertThat(Json.parse(rest.body()).get("items").get(shade.toString()).asInt()).isEqualTo(2);
        order = order();
        assertThat(order.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");
        assertThat(order.get("status").asString()).isEqualTo("CLOSED");
        assertThat(order.get("timeline").valueStream().map(e -> e.get("type").asString()))
                .containsExactly("PLACED", "PAID", "SHIPPED", "SHIPPED", "FULFILLED");
        assertThat(Json.parse(api.get(orderPath("/shipments"), shop.auth()).body()).get("data")).hasSize(2);
        assertThat(code(ship("{}"))).isEqualTo("nothing_to_ship");
    }

    @Test
    void nothingShipsBeyondWhatWasOrderedOrBeforePayment() {
        assertThat(code(ship("{\"lines\":[{\"variantId\":\"" + lamp + "\",\"quantity\":4}]}"))).isEqualTo("quantity_exceeds_remaining");
        assertThat(code(ship("{\"lines\":[{\"variantId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}"))).isEqualTo("quantity_exceeds_remaining");
        ship("{\"lines\":[{\"variantId\":\"" + lamp + "\",\"quantity\":3}]}");
        assertThat(code(ship("{\"lines\":[{\"variantId\":\"" + lamp + "\",\"quantity\":1}]}"))).as("already all shipped")
                .isEqualTo("quantity_exceeds_remaining");
        assertThat(code(api.post(orderPath("/cancellation"), "{\"reason\":\"other\"}", shop.auth()))).isEqualTo("order_has_shipments");
    }

    @Test
    void unpaidOrdersCannotShip() {
        StorefrontFlows storefront = new StorefrontFlows(api, shop);
        String unpaid = storefront.placeOrder(lamp, 1, uniqueEmail()).get("orderId").asString();
        HttpResponse<String> response = api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + unpaid + "/shipments", "{}", shop.auth());
        assertThat(code(response)).isEqualTo("order_not_paid");
    }

    /** Two warehouse workers ship 2 of the 3 lamps at the same moment: the order lock lets only one through. */
    @Test
    void concurrentShipmentsCannotShipTheSameUnitsTwice() throws Exception {
        String body = "{\"lines\":[{\"variantId\":\"" + lamp + "\",\"quantity\":2}]}";
        List<HttpResponse<String>> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            results = executor.invokeAll(List.<Callable<HttpResponse<String>>>of(() -> ship(body), () -> ship(body))).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 422);
    }

    @Test
    void cancellingAShipmentInTransitReopensTheOrderButDeliveredOnesStay() {
        String shipment = Json.parse(ship("{}").body()).get("id").asString();
        assertThat(order().get("status").asString()).isEqualTo("CLOSED");

        HttpResponse<String> cancelled = api.post(orderPath("/shipments/" + shipment + "/cancellation"), "{}", shop.auth());
        assertThat(Json.parse(cancelled.body()).get("status").asString()).isEqualTo("CANCELLED");
        JsonNode order = order();
        assertThat(order.get("status").asString()).isEqualTo("OPEN");
        assertThat(order.get("fulfillmentStatus").asString()).isEqualTo("UNFULFILLED");

        String again = Json.parse(ship("{}").body()).get("id").asString();
        assertThat(Json.parse(api.post(orderPath("/shipments/" + again + "/delivery"), "{}", shop.auth()).body()).get("status").asString())
                .isEqualTo("DELIVERED");
        assertThat(code(api.post(orderPath("/shipments/" + again + "/cancellation"), "{}", shop.auth()))).isEqualTo("shipment_not_in_transit");
    }

    private HttpResponse<String> ship(String body) {
        return api.post(orderPath("/shipments"), body, shop.auth());
    }

    private JsonNode order() {
        return Json.parse(api.get(orderPath(""), shop.auth()).body());
    }

    private String orderPath(String suffix) {
        return "/admin/v1/stores/" + shop.storeId() + "/orders/" + orderId + suffix;
    }

    private static String code(HttpResponse<String> response) {
        JsonNode body = Json.parse(response.body());
        return body.has("code") ? body.get("code").asString() : "HTTP " + response.statusCode();
    }
}
