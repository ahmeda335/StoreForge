package dev.storeforge.stores;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The cross-tenant leakage suite. Endpoints are DISCOVERED from Spring MVC's handler registry, so
 * every new tenant-scoped endpoint is covered automatically. A member of store A calls every
 * {@code /admin/v1/stores/{storeId}/...} endpoint with store B's id and must get 404 every time:
 * never data, never 403 (which would confirm B exists), never a successful write.
 */
class TenantIsolationIT extends IntegrationTest {

    /** Valid request bodies, so requests get past Bean Validation and actually reach the tenant check. */
    private static final Map<String, String> SAMPLE_BODIES = Map.ofEntries(
            Map.entry("PATCH /admin/v1/stores/{storeId}", "{\"name\":\"Hijacked\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/roles", "{\"name\":\"x\",\"permissions\":[]}"),
            Map.entry("POST /admin/v1/stores/{storeId}/invitations", "{\"email\":\"x@example.test\",\"roleId\":\"" + UUID.randomUUID() + "\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/invitations/acceptance", "{\"token\":\"not-a-real-token\"}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/members/{userId}", "{\"roleId\":\"" + UUID.randomUUID() + "\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/products", "{\"title\":\"Injected\",\"options\":[],\"variants\":[{\"price\":\"1.00\",\"options\":[]}]}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/products/{productId}", "{\"title\":\"Hijacked\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/products/{productId}/variants", "{\"price\":\"1.00\",\"options\":[]}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/products/{productId}/variants/{variantId}", "{\"price\":\"1.00\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/variants/{variantId}/inventory/adjustments", "{\"delta\":5,\"reason\":\"RECEIVED\"}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/variants/{variantId}/inventory", "{\"tracked\":false}"),
            Map.entry("POST /admin/v1/stores/{storeId}/orders/{orderId}/cancellation", "{\"reason\":\"other\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/orders/{orderId}/manual-payment", "{}"),
            Map.entry("POST /admin/v1/stores/{storeId}/orders/{orderId}/refunds", "{\"amount\":\"1.00\",\"reason\":\"other\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/webhook-subscriptions", "{\"url\":\"https://example.com/hooks\",\"topics\":[\"orders/create\"]}"),
            Map.entry("POST /admin/v1/stores/{storeId}/webhook-subscriptions/{subscriptionId}/replays",
                    "{\"statuses\":[\"DEAD\"],\"since\":\"2026-01-01T00:00:00Z\"}"));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    StoreFlows stores;
    Owner storeA;
    Owner storeB;

    @BeforeEach
    void twoTenants() {
        stores = new StoreFlows(api, mailbox);
        storeA = stores.newOwnerWithStore(uniqueEmail());
        storeB = stores.newOwnerWithStore(uniqueEmail());
    }

    @Test
    void everyTenantScopedEndpointHidesOtherStores() {
        List<String> endpoints = tenantScopedEndpoints();
        assertThat(endpoints).as("discovered endpoints").hasSizeGreaterThanOrEqualTo(12);
        String storeBName = Json.parse(api.get("/admin/v1/stores/" + storeB.storeId(), storeB.auth()).body()).get("name").asString();

        for (String endpoint : endpoints) {
            String method = endpoint.substring(0, endpoint.indexOf(' '));
            String pattern = endpoint.substring(endpoint.indexOf(' ') + 1);
            String path = pattern.replace("{storeId}", storeB.storeId().toString()).replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            String body = SAMPLE_BODIES.getOrDefault(endpoint, method.equals("GET") || method.equals("DELETE") ? null : "{}");

            HttpResponse<String> response = api.request(method, path, body, storeA.auth());

            assertThat(response.statusCode()).as(endpoint + " → " + response.body()).isEqualTo(404);
            assertThat(response.body()).as(endpoint).doesNotContain(storeBName);
        }
        assertThat(Json.parse(api.get("/admin/v1/stores/" + storeB.storeId(), storeB.auth()).body()).get("name").asString())
                .as("store B must be unchanged").isEqualTo(storeBName);
    }

    @Test
    void otherStoresResourceIdsAreNotFoundInsideMyStore() {
        UUID roleOfB = stores.roleId(storeB, "Staff");
        UUID ownerOfB = stores.userId(storeB.session());
        String base = "/admin/v1/stores/" + storeA.storeId();

        HttpResponse<String> deleteForeignRole = api.delete(base + "/roles/" + roleOfB, storeA.auth());
        HttpResponse<String> changeForeignMember = api.patch(base + "/members/" + ownerOfB,
                Json.object("roleId", stores.roleId(storeA, "Staff").toString()), storeA.auth());
        HttpResponse<String> assignForeignRole = api.patch(base + "/members/" + stores.userId(storeA.session()),
                Json.object("roleId", roleOfB.toString()), storeA.auth());
        HttpResponse<String> inviteIntoForeignRole = api.post(base + "/invitations",
                Json.object("email", uniqueEmail(), "roleId", roleOfB.toString()), storeA.auth());

        assertThat(List.of(deleteForeignRole, changeForeignMember, assignForeignRole, inviteIntoForeignRole))
                .allSatisfy(response -> assertThat(response.statusCode()).as(response.body()).isEqualTo(404));
        assertThat(stores.roleId(storeB, "Staff")).as("B's role still exists").isEqualTo(roleOfB);
    }

    private List<String> tenantScopedEndpoints() {
        List<String> endpoints = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/admin/v1/stores/{storeId}")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    endpoints.add(method.name() + " " + pattern);
                }
            }
        }
        return endpoints;
    }
}
