package dev.storeforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Shopper-side HTTP steps (anonymous; store chosen by hostname; cart token header). */
public final class StorefrontFlows {

    public static final String ADDRESS = """
            {"name":"Ada Lovelace","line1":"12 Analytical St","city":"London","postalCode":"N1 9GU","country":"GB"}""";

    private final HttpTestClient api;
    private final String host;

    public StorefrontFlows(HttpTestClient api, Owner shop) {
        this.api = api;
        String handle = Json.parse(api.get("/admin/v1/stores/" + shop.storeId(), shop.auth()).body()).get("handle").asString();
        this.host = handle + ".storeforge.localhost";
    }

    public record Cart(UUID id, String token) {
    }

    public Cart newCart() {
        HttpResponse<String> response = api.post("/storefront/v1/carts", "", headers(null));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = Json.parse(response.body());
        return new Cart(UUID.fromString(body.get("cartId").asString()), body.get("cartToken").asString());
    }

    public HttpResponse<String> addItem(Cart cart, UUID variantId, int quantity) {
        return api.post("/storefront/v1/carts/" + cart.id() + "/items",
                "{\"variantId\":\"" + variantId + "\",\"quantity\":" + quantity + "}", headers(cart.token()));
    }

    public HttpResponse<String> getCart(Cart cart) {
        return api.get("/storefront/v1/carts/" + cart.id(), headers(cart.token()));
    }

    public HttpResponse<String> startCheckout(Cart cart, String email) {
        return api.post("/storefront/v1/checkouts",
                "{\"cartId\":\"" + cart.id() + "\",\"email\":\"" + email + "\",\"shippingAddress\":" + ADDRESS + "}",
                headers(cart.token()));
    }

    /** Cart with the given variant, checkout started with email + address. Returns the checkout id. */
    public UUID readyCheckout(Cart cart, UUID variantId, int quantity, String email) {
        assertThat(addItem(cart, variantId, quantity).statusCode()).isEqualTo(200);
        HttpResponse<String> started = startCheckout(cart, email);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        return UUID.fromString(Json.parse(started.body()).get("id").asString());
    }

    /** Full shopper flow → completed checkout with an order. Returns the completion response body. */
    public JsonNode placeOrder(UUID variantId, int quantity, String email) {
        Cart cart = newCart();
        UUID checkoutId = readyCheckout(cart, variantId, quantity, email);
        String total = Json.parse(getCheckout(cart, checkoutId).body()).get("total").get("amount").asString();
        HttpResponse<String> completed = complete(cart, checkoutId, total, UUID.randomUUID().toString());
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        return Json.parse(completed.body());
    }

    public HttpResponse<String> getCheckout(Cart cart, UUID checkoutId) {
        return api.get("/storefront/v1/checkouts/" + checkoutId, headers(cart.token()));
    }

    public HttpResponse<String> complete(Cart cart, UUID checkoutId, String expectedTotal, String idempotencyKey) {
        return complete(cart.token(), checkoutId, expectedTotal, idempotencyKey);
    }

    public HttpResponse<String> complete(String cartToken, UUID checkoutId, String expectedTotal, String idempotencyKey) {
        Map<String, String> headers = headers(cartToken);
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return api.post("/storefront/v1/checkouts/" + checkoutId + "/completion",
                "{\"expectedTotal\":\"" + expectedTotal + "\"}", headers);
    }

    public Map<String, String> headers(String cartToken) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Forwarded-Host", host);
        if (cartToken != null) {
            headers.put("X-Cart-Token", cartToken);
        }
        return headers;
    }
}
