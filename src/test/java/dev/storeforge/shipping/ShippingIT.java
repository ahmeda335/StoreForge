package dev.storeforge.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import dev.storeforge.support.StorefrontFlows.Cart;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Shipping rates quoted by destination and weight, chosen at checkout, re-validated at completion. */
class ShippingIT extends IntegrationTest {

    Owner shop;
    StorefrontFlows storefront;
    UUID lamp;    // 20.00, 800 g
    String standard;
    String heavy;
    String europe;

    @BeforeEach
    void shopWithRates() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        lamp = variant("Lamp", "20.00", 800);
        storefront = new StorefrontFlows(api, shop);
        standard = rate("{\"name\":\"Standard\",\"maxWeightGrams\":2000,\"price\":\"4.90\",\"freeOver\":\"50.00\"}");
        heavy = rate("{\"name\":\"Heavy parcel\",\"minWeightGrams\":2000,\"price\":\"12.00\"}");
        europe = rate("{\"name\":\"EU Express\",\"countries\":[\"FR\",\"DE\"],\"price\":\"9.90\"}");
    }

    record Checkout(Cart cart, UUID id) {
    }

    @Test
    void ratesDependOnDestinationAndWeightAndTheChosenOneIsCharged() {
        Checkout checkout = checkout(1, "GB");   // 800 g, United Kingdom
        assertThat(rateNames(view(checkout))).containsExactly("Standard");

        Checkout heavyOne = checkout(3, "FR");   // 2400 g, France
        assertThat(rateNames(view(heavyOne))).containsExactly("EU Express", "Heavy parcel");

        JsonNode chosen = Json.parse(select(heavyOne, heavy).body());
        assertThat(chosen.get("shipping").get("amount").asString()).isEqualTo("12.00");
        assertThat(chosen.get("total").get("amount").asString()).isEqualTo("72.00");
        HttpResponse<String> completed = storefront.complete(heavyOne.cart(), heavyOne.id(), "72.00", UUID.randomUUID().toString());
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        JsonNode order = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/orders/" + Json.parse(completed.body()).get("orderId").asString(),
                shop.auth()).body());
        assertThat(order.get("shipping").get("amount").asString()).isEqualTo("12.00");
        assertThat(order.get("total").get("amount").asString()).isEqualTo("72.00");
    }

    /** Free shipping looks at what the customer pays for the goods: a discount can push the order below the threshold. */
    @Test
    void freeShippingIsMeasuredAfterDiscounts() {
        Checkout two = checkout(2, "GB");        // 40.00, 1600 g: below the threshold
        assertThat(Json.parse(select(two, standard).body()).get("shipping").get("amount").asString()).isEqualTo("4.90");

        UUID light = variant("Card", "30.00", 100);
        Checkout cards = checkoutOf(light, 2, "GB");   // 60.00, 200 g
        assertThat(Json.parse(select(cards, standard).body()).get("shipping").get("amount").asString()).as("over 50.00: free").isEqualTo("0.00");
        api.post("/admin/v1/stores/" + shop.storeId() + "/discounts", "{\"code\":\"TWENTY\",\"kind\":\"PERCENTAGE\",\"value\":\"20\"}", shop.auth());
        JsonNode discounted = Json.parse(api.request("PUT", "/storefront/v1/checkouts/" + cards.id() + "/discount", "{\"code\":\"TWENTY\"}",
                storefront.headers(cards.cart().token())).body());
        assertThat(discounted.get("shipping").get("amount").asString()).as("48.00 after discount: charged").isEqualTo("4.90");
        assertThat(discounted.get("total").get("amount").asString()).isEqualTo("52.90");
    }

    @Test
    void aStoreWithRatesRequiresAValidChoiceAtCompletion() {
        Checkout checkout = checkout(1, "FR");
        String total = Json.parse(storefront.getCheckout(checkout.cart(), checkout.id()).body()).get("total").get("amount").asString();
        HttpResponse<String> none = storefront.complete(checkout.cart(), checkout.id(), total, UUID.randomUUID().toString());
        assertThat(none.statusCode()).isEqualTo(422);
        assertThat(none.body()).contains("shippingRate");

        select(checkout, europe);
        api.patch("/admin/v1/stores/" + shop.storeId() + "/shipping-rates/" + europe, "{\"active\":false}", shop.auth());
        JsonNode view = view(checkout);
        assertThat(view.get("shippingRate").get("available").asBoolean()).isFalse();
        assertThat(view.get("shipping").get("amount").asString()).as("not charged while unavailable").isEqualTo("0.00");
        HttpResponse<String> stale = storefront.complete(checkout.cart(), checkout.id(), "29.90", UUID.randomUUID().toString());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(Json.parse(stale.body()).get("code").asString()).isEqualTo("shipping_rate_not_available");

        assertThat(select(checkout, heavy).statusCode()).as("Heavy doesn't fit 800 g").isEqualTo(409);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private UUID variant(String title, String price, int grams) {
        HttpResponse<String> created = api.post("/admin/v1/stores/" + shop.storeId() + "/products", """
                {"title":"%s","status":"ACTIVE","options":[],"variants":[{"price":"%s","weightGrams":%d,"options":[]}]}"""
                .formatted(title, price, grams), shop.auth());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID id = UUID.fromString(Json.parse(created.body()).get("variants").get(0).get("id").asString());
        api.post("/admin/v1/stores/" + shop.storeId() + "/variants/" + id + "/inventory/adjustments", "{\"delta\":50,\"reason\":\"RECEIVED\"}",
                shop.auth());
        return id;
    }

    private String rate(String body) {
        HttpResponse<String> created = api.post("/admin/v1/stores/" + shop.storeId() + "/shipping-rates", body, shop.auth());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return Json.parse(created.body()).get("id").asString();
    }

    private Checkout checkout(int quantity, String country) {
        return checkoutOf(lamp, quantity, country);
    }

    private Checkout checkoutOf(UUID variant, int quantity, String country) {
        Cart cart = storefront.newCart();
        storefront.addItem(cart, variant, quantity);
        HttpResponse<String> started = api.post("/storefront/v1/checkouts", """
                {"cartId":"%s","email":"%s","shippingAddress":{"name":"Ada","line1":"1 Main St","city":"Town","postalCode":"1000","country":"%s"}}"""
                .formatted(cart.id(), uniqueEmail(), country), storefront.headers(cart.token()));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        return new Checkout(cart, UUID.fromString(Json.parse(started.body()).get("id").asString()));
    }

    private JsonNode view(Checkout checkout) {
        return Json.parse(storefront.getCheckout(checkout.cart(), checkout.id()).body());
    }

    private HttpResponse<String> select(Checkout checkout, String rateId) {
        return api.request("PUT", "/storefront/v1/checkouts/" + checkout.id() + "/shipping-rate", "{\"rateId\":\"" + rateId + "\"}",
                storefront.headers(checkout.cart().token()));
    }

    private static List<String> rateNames(JsonNode view) {
        return view.get("shippingRates").valueStream().map(r -> r.get("name").asString()).toList();
    }
}
