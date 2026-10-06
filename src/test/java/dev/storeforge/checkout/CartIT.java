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

class CartIT extends IntegrationTest {

    CatalogFlows catalog;
    Owner shop;
    StorefrontFlows storefront;
    UUID variant;

    @BeforeEach
    void shopWithProduct() {
        catalog = new CatalogFlows(api);
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        storefront = new StorefrontFlows(api, shop);
        variant = catalog.createVariant(shop, "Mug");   // price 10.00
        catalog.adjustStock(shop, variant, 3, "RECEIVED");
    }

    @Test
    void addingTheSameItemIncrementsAndPricesAreLive() {
        Cart cart = storefront.newCart();

        storefront.addItem(cart, variant, 1);
        var body = Json.parse(storefront.addItem(cart, variant, 2).body());

        assertThat(body.get("items").size()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(body.get("subtotal").get("amount").asString()).isEqualTo("30.00");
        assertThat(body.get("items").get(0).get("inStock").asBoolean()).isTrue();

        UUID productId = UUID.fromString(Json.parse(api.get(CatalogFlows.products(shop) + "?q=Mug", shop.auth()).body())
                .get("data").get(0).get("id").asString());
        api.patch(CatalogFlows.products(shop) + "/" + productId + "/variants/" + variant, "{\"price\":\"12.00\"}", shop.auth());
        assertThat(Json.parse(storefront.getCart(cart).body()).get("subtotal").get("amount").asString())
                .as("a cart is not a price promise").isEqualTo("36.00");
    }

    @Test
    void quantityChangesRemovalAndLimits() {
        Cart cart = storefront.newCart();
        storefront.addItem(cart, variant, 2);

        var updated = Json.parse(api.patch("/storefront/v1/carts/" + cart.id() + "/items/" + variant, "{\"quantity\":5}",
                storefront.headers(cart.token())).body());
        assertThat(updated.get("items").get(0).get("inStock").asBoolean()).as("5 wanted, 3 in stock").isFalse();

        HttpResponse<String> tooMany = storefront.addItem(cart, variant, 99);
        assertThat(tooMany.statusCode()).isEqualTo(422);
        assertThat(code(tooMany)).isEqualTo("quantity_limit");

        var removed = Json.parse(api.delete("/storefront/v1/carts/" + cart.id() + "/items/" + variant,
                storefront.headers(cart.token())).body());
        assertThat(removed.get("items").size()).isZero();
    }

    @Test
    void theCartTokenIsRequiredAndCartsDoNotCrossStores() {
        Cart cart = storefront.newCart();
        Owner otherShop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        StorefrontFlows otherStorefront = new StorefrontFlows(api, otherShop);

        HttpResponse<String> noToken = api.get("/storefront/v1/carts/" + cart.id(), storefront.headers(null));
        HttpResponse<String> wrongToken = storefront.getCart(new Cart(cart.id(), "guessed-token"));
        HttpResponse<String> otherHost = otherStorefront.getCart(cart);

        assertThat(noToken.statusCode()).isEqualTo(404);
        assertThat(wrongToken.statusCode()).isEqualTo(404);
        assertThat(otherHost.statusCode()).as("cart of store A through store B's hostname").isEqualTo(404);
        assertThat(code(otherHost)).isEqualTo("cart_not_found");
        assertThat(storefront.getCart(cart).statusCode()).isEqualTo(200);
    }

    @Test
    void onlySellableItemsCanBeAdded() {
        UUID draftVariant = UUID.fromString(Json.parse(catalog.create(shop, """
                {"title":"Secret draft","status":"DRAFT","options":[],"variants":[{"price":"1.00","options":[]}]}""").body())
                .get("variants").get(0).get("id").asString());
        Owner otherShop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        UUID foreignVariant = catalog.createVariant(otherShop, "Foreign");
        Cart cart = storefront.newCart();

        assertThat(code(storefront.addItem(cart, draftVariant, 1))).isEqualTo("item_unavailable");
        assertThat(code(storefront.addItem(cart, foreignVariant, 1))).isEqualTo("item_unavailable");
    }

    @Test
    void cartCreationResponseIsNotCacheable() {
        HttpResponse<String> response = api.post("/storefront/v1/carts", "", storefront.headers(null));

        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    private static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
