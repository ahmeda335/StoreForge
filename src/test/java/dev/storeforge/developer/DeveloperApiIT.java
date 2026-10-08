package dev.storeforge.developer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.DeveloperFlows;
import dev.storeforge.support.DeveloperFlows.Key;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/** The developer API's contract: credentials, store binding, scopes, versions, sync feeds, idempotent writes. */
class DeveloperApiIT extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    Owner shop;
    DeveloperFlows developer;
    UUID variant;

    @BeforeEach
    void shop() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        developer = new DeveloperFlows(api);
        variant = new CatalogFlows(api).createVariant(shop, "Lamp");
        new CatalogFlows(api).adjustStock(shop, variant, 10, "RECEIVED");
    }

    /** Architecture rule: no developer endpoint ships without a scope declaration. */
    @Test
    void everyDeveloperEndpointDeclaresItsScope() {
        List<String> undeclared = new ArrayList<>();
        int endpoints = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (entry.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/"))) {
                endpoints++;
                if (!entry.getValue().hasMethodAnnotation(RequiresScope.class)) {
                    undeclared.add(entry.getKey().toString());
                }
            }
        }
        assertThat(endpoints).isGreaterThanOrEqualTo(9);
        assertThat(undeclared).as("developer endpoints without @RequiresScope").isEmpty();
    }

    @Test
    void anApiKeyIsShownOnceAndWorksWithinItsStoreAndScopes() {
        Key key = developer.createKey(shop, "read_orders", "read_inventory");
        assertThat(key.token()).startsWith("sfk_");
        JsonNode listed = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/api-keys", shop.auth()).body()).get("data").get(0);
        assertThat(listed.get("token").isNull()).isTrue();
        assertThat(key.token()).startsWith(listed.get("tokenPrefix").asString());

        HttpResponse<String> orders = api.get(DeveloperFlows.path(shop.storeId(), "/orders"), key.auth());
        assertThat(orders.statusCode()).as(orders.body()).isEqualTo(200);
        assertThat(orders.headers().firstValue("X-StoreForge-Api-Version")).hasValue("2026-10");
        assertThat(orders.headers().firstValue("X-RateLimit-Limit")).hasValue("40");
        assertThat(orders.headers().firstValue("X-RateLimit-Remaining")).isPresent();

        HttpResponse<String> products = api.get(DeveloperFlows.path(shop.storeId(), "/products"), key.auth());
        assertThat(products.statusCode()).isEqualTo(403);
        assertThat(code(products)).isEqualTo("insufficient_scope");
        assertThat(products.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("insufficient_scope").contains("read_products");

        Owner other = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        assertThat(api.get(DeveloperFlows.path(other.storeId(), "/orders"), key.auth()).statusCode()).as("another store").isEqualTo(404);
        assertThat(code(api.get("/api/2019-01/stores/" + shop.storeId() + "/orders", key.auth()))).isEqualTo("unsupported_api_version");
    }

    @Test
    void missingWrongAndRevokedCredentialsAreRejectedImmediately() {
        HttpResponse<String> none = api.get(DeveloperFlows.path(shop.storeId(), "/orders"));
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
        assertThat(code(none)).isEqualTo("unauthenticated");
        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/orders"), Map.of("Authorization", "Bearer sfk_forged")).statusCode())
                .isEqualTo(401);
        String userJwt = shop.auth().get("Authorization");
        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/orders"), Map.of("Authorization", userJwt)).statusCode())
                .as("a merchant session token is not an API credential").isEqualTo(401);

        Key key = developer.createKey(shop, "read_orders");
        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/orders"), key.auth()).statusCode()).isEqualTo(200);
        assertThat(api.delete("/admin/v1/stores/" + shop.storeId() + "/api-keys/" + key.id(), shop.auth()).statusCode()).isEqualTo(204);
        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/orders"), key.auth()).statusCode()).as("revoked: next request").isEqualTo(401);
    }

    /** Anti-escalation: a key can never do more than the person who created it. */
    @Test
    void keysCannotExceedTheirCreatorsPermissions() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        UUID role = stores.createRole(shop, "Integrator", "apps:manage", "orders:read");
        var integrator = stores.addMember(shop, uniqueEmail(), role);
        Map<String, String> auth = StoreFlows.bearer(integrator.accessToken());

        HttpResponse<String> tooMuch = developer.createKey(auth, shop.storeId(), "read_orders", "write_inventory");
        assertThat(tooMuch.statusCode()).isEqualTo(403);
        assertThat(developer.createKey(auth, shop.storeId(), "read_orders").statusCode()).isEqualTo(201);
        assertThat(code(developer.createKey(shop.auth(), shop.storeId(), "read_everything"))).isEqualTo("unknown_scope");
        var staff = stores.addMember(shop, uniqueEmail(), stores.roleId(shop, "Staff"));
        assertThat(developer.createKey(StoreFlows.bearer(staff.accessToken()), shop.storeId(), "read_orders").statusCode())
                .as("needs apps:manage").isEqualTo(403);
    }

    /** "Everything that changed since X", resumable by cursor: how integrations catch up after downtime. */
    @Test
    void ordersCanBeSyncedByChangeTime() {
        Key key = developer.createKey(shop, "read_orders");
        StorefrontFlows storefront = new StorefrontFlows(api, shop);
        List<String> placed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            placed.add(storefront.place(variant, 1, uniqueEmail()).orderId().toString());
        }

        JsonNode first = Json.parse(api.get(DeveloperFlows.path(shop.storeId(), "/orders?limit=2"), key.auth()).body());
        JsonNode second = Json.parse(api.get(DeveloperFlows.path(shop.storeId(), "/orders?limit=2&cursor="
                + first.get("page").get("nextCursor").asString()), key.auth()).body());
        List<String> seen = new ArrayList<>();
        first.get("data").valueStream().forEach(o -> seen.add(o.get("id").asString()));
        second.get("data").valueStream().forEach(o -> seen.add(o.get("id").asString()));
        assertThat(seen).containsExactlyElementsOf(placed);
        String lastSync = second.get("data").get(0).get("updatedAt").asString();

        api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + placed.getFirst() + "/manual-payment", "{}", shop.auth());
        JsonNode changed = Json.parse(api.get(DeveloperFlows.path(shop.storeId(), "/orders?updatedAfter=" + lastSync), key.auth()).body());

        assertThat(changed.get("data").valueStream().map(o -> o.get("id").asString())).containsExactly(placed.getFirst());
        JsonNode detail = Json.parse(api.get(DeveloperFlows.path(shop.storeId(), "/orders/" + placed.getFirst()), key.auth()).body());
        assertThat(detail.get("financialStatus").asString()).isEqualTo("PAID");
        assertThat(detail.get("lineItems").get(0).get("productTitle").asString()).isEqualTo("Lamp");
    }

    @Test
    void stockAdjustmentsAreIdempotentAndAuditedAsTheApiKey() {
        Key key = developer.createKey(shop, "read_inventory", "write_inventory");
        JsonNode item = Json.parse(api.get(DeveloperFlows.path(shop.storeId(), "/inventory-items"), key.auth()).body()).get("data").get(0);
        String itemPath = DeveloperFlows.path(shop.storeId(), "/inventory-items/" + item.get("id").asString() + "/adjustments");
        Map<String, String> headers = new HashMap<>(key.auth());
        headers.put("Idempotency-Key", UUID.randomUUID().toString());

        HttpResponse<String> first = api.post(itemPath, "{\"delta\":5,\"reason\":\"RECEIVED\"}", headers);
        HttpResponse<String> retry = api.post(itemPath, "{\"delta\":5,\"reason\":\"RECEIVED\"}", headers);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(Json.parse(retry.body()).get("onHand").asInt()).isEqualTo(15);
        assertThat(Json.parse(api.get(DeveloperFlows.path(shop.storeId(), "/inventory-items/" + item.get("id").asString()), key.auth())
                .body()).get("onHand").asInt()).as("applied once").isEqualTo(15);
        assertThat(code(api.post(itemPath, "{\"delta\":5,\"reason\":\"RECEIVED\"}", key.auth()))).isEqualTo("idempotency_key_required");

        JsonNode audit = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/audit-log", shop.auth()).body()).get("data").get(0);
        assertThat(audit.get("action").asString()).isEqualTo("inventory.adjusted");
        assertThat(audit.get("actorType").asString()).isEqualTo("API_KEY");
        assertThat(audit.get("actorId").asString()).isEqualTo(key.id().toString());
    }

    /** Apps subscribe to what they may read, see only their own subscriptions, and lose them on revocation. */
    @Test
    void appOwnedWebhookSubscriptionsFollowScopesAndRevocation() {
        Key key = developer.createKey(shop, "read_inventory");
        String path = DeveloperFlows.path(shop.storeId(), "/webhook-subscriptions");

        HttpResponse<String> orders = api.post(path, "{\"url\":\"https://example.com/hooks\",\"topics\":[\"orders/paid\"]}", key.auth());
        assertThat(orders.statusCode()).isEqualTo(403);
        HttpResponse<String> inventory = api.post(path, "{\"url\":\"https://example.com/hooks\",\"topics\":[\"inventory_levels/update\"]}",
                key.auth());
        assertThat(inventory.statusCode()).as(inventory.body()).isEqualTo(201);
        String subscriptionId = Json.parse(inventory.body()).get("id").asString();
        api.post("/admin/v1/stores/" + shop.storeId() + "/webhook-subscriptions",
                "{\"url\":\"https://example.com/merchant\",\"topics\":[\"orders/paid\"]}", shop.auth());

        assertThat(Json.parse(api.get(path, key.auth()).body()).get("data")).hasSize(1);
        JsonNode merchantView = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/webhook-subscriptions", shop.auth()).body()).get("data");
        assertThat(merchantView).hasSize(2);
        assertThat(merchantView.valueStream().filter(s -> !s.get("ownerCredentialId").isNull()).map(s -> s.get("id").asString()))
                .containsExactly(subscriptionId);

        api.delete("/admin/v1/stores/" + shop.storeId() + "/api-keys/" + key.id(), shop.auth());

        JsonNode after = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/webhook-subscriptions", shop.auth()).body()).get("data");
        assertThat(after).as("the app's subscription went with its key; the merchant's stays").hasSize(1);
    }

    @Test
    void theOpenApiDocumentDescribesOnlyTheDeveloperApi() {
        HttpResponse<String> response = api.get("/developer/openapi");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode doc = Json.parse(response.body());
        assertThat(doc.get("info").get("version").asString()).isEqualTo("2026-10");
        List<String> paths = new ArrayList<>();
        doc.get("paths").propertyNames().forEach(paths::add);
        assertThat(paths).contains("/api/{apiVersion}/stores/{storeId}/orders",
                "/api/{apiVersion}/stores/{storeId}/inventory-items/{itemId}/adjustments").allMatch(p -> p.startsWith("/api/"));
        assertThat(doc.get("components").get("securitySchemes").has("bearer")).isTrue();
    }

    static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
