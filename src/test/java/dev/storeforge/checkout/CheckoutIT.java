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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheckoutIT extends IntegrationTest {

    CatalogFlows catalog;
    Owner shop;
    StorefrontFlows storefront;
    UUID variant;
    UUID productId;

    @BeforeEach
    void shopWithStock() {
        catalog = new CatalogFlows(api);
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        storefront = new StorefrontFlows(api, shop);
        var created = Json.parse(catalog.create(shop, """
                {"title":"Notebook","status":"ACTIVE","options":[],"variants":[{"sku":"NB-1","price":"14.99","options":[]}]}""").body());
        productId = UUID.fromString(created.get("id").asString());
        variant = UUID.fromString(created.get("variants").get(0).get("id").asString());
        catalog.adjustStock(shop, variant, 5, "RECEIVED");
    }

    @Test
    void completingAVerifiedCheckoutReservesStockFreezesPricesAndRecordsTheCustomer() {
        Cart cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 2, "Ada@Example.test");
        var open = Json.parse(storefront.getCheckout(cart, checkoutId).body());
        assertThat(open.get("status").asString()).isEqualTo("OPEN");
        assertThat(open.get("total").get("amount").asString()).isEqualTo("29.98");

        HttpResponse<String> completed = storefront.complete(cart, checkoutId, "29.98", UUID.randomUUID().toString());

        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        assertThat(completed.headers().firstValue("Idempotent-Replayed")).hasValue("false");
        var body = Json.parse(completed.body());
        assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(body.get("email").asString()).isEqualTo("ada@example.test");
        assertThat(body.get("lines").get(0).get("unitPrice").get("amount").asString()).isEqualTo("14.99");
        assertThat(body.has("reservationExpiresAt")).isTrue();
        assertThat(stock()).as("onHand, reserved").containsExactly(5, 2);

        var customers = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/customers", shop.auth()).body()).get("data");
        assertThat(customers.get(0).get("email").asString()).isEqualTo("ada@example.test");
        assertThat(customers.get(0).get("firstName").asString()).isEqualTo("Ada");

        // The snapshot doesn't move when the catalog does.
        api.patch(CatalogFlows.products(shop) + "/" + productId + "/variants/" + variant, "{\"price\":\"99.00\"}", shop.auth());
        assertThat(Json.parse(storefront.getCheckout(cart, checkoutId).body()).get("total").get("amount").asString()).isEqualTo("29.98");

        HttpResponse<String> addAfter = storefront.addItem(cart, variant, 1);
        assertThat(addAfter.statusCode()).isEqualTo(409);
        assertThat(code(addAfter)).isEqualTo("cart_closed");
    }

    @Test
    void aChangedTotalIsRejectedAndNothingIsReserved() {
        Cart cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 1, uniqueEmail());
        api.patch(CatalogFlows.products(shop) + "/" + productId + "/variants/" + variant, "{\"price\":\"15.99\"}", shop.auth());

        HttpResponse<String> response = storefront.complete(cart, checkoutId, "14.99", UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("total_changed");
        assertThat(Json.parse(response.body()).get("detail").asString()).contains("15.99");
        assertThat(stock()).containsExactly(5, 0);
        assertThat(storefront.complete(cart, checkoutId, "15.99", UUID.randomUUID().toString()).statusCode())
                .as("confirming the new total works").isEqualTo(200);
    }

    @Test
    void insufficientStockFailsCleanlyAndTheCheckoutStaysOpen() {
        Cart cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 6, uniqueEmail());

        HttpResponse<String> response = storefront.complete(cart, checkoutId, "89.94", UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("insufficient_stock");
        assertThat(stock()).containsExactly(5, 0);
        assertThat(Json.parse(storefront.getCheckout(cart, checkoutId).body()).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void completionNeedsContactDetails() {
        Cart cart = storefront.newCart();
        storefront.addItem(cart, variant, 1);
        UUID checkoutId = UUID.fromString(Json.parse(api.post("/storefront/v1/checkouts",
                "{\"cartId\":\"" + cart.id() + "\"}", storefront.headers(cart.token())).body()).get("id").asString());

        HttpResponse<String> response = storefront.complete(cart, checkoutId, "14.99", UUID.randomUUID().toString());

        assertThat(code(response)).isEqualTo("checkout_incomplete");
        assertThat(Json.parse(response.body()).get("detail").asString()).contains("email", "shippingAddress");
    }

    @Test
    void startingTwiceReturnsTheSameOpenCheckout() {
        Cart cart = storefront.newCart();
        storefront.addItem(cart, variant, 1);

        HttpResponse<String> first = storefront.startCheckout(cart, uniqueEmail());
        HttpResponse<String> second = storefront.startCheckout(cart, uniqueEmail());

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(Json.parse(second.body()).get("id").asString()).isEqualTo(Json.parse(first.body()).get("id").asString());
    }

    @Test
    void oneEmailIsOneCustomerAcrossCheckouts() {
        String email = uniqueEmail();
        for (int i = 0; i < 2; i++) {
            Cart cart = storefront.newCart();
            UUID checkoutId = storefront.readyCheckout(cart, variant, 1, email);
            assertThat(storefront.complete(cart, checkoutId, "14.99", UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
        }

        var customers = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/customers", shop.auth()).body()).get("data");
        assertThat(customers.valueStream().filter(c -> c.get("email").asString().equals(email)).count()).isEqualTo(1);
    }

    private int[] stock() {
        var level = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/variants/" + variant + "/inventory", shop.auth()).body());
        return new int[] {level.get("onHand").asInt(), level.get("reserved").asInt()};
    }

    private static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
