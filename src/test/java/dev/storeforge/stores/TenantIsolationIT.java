package dev.storeforge.stores;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.SampleRequests;
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
            String body = SampleRequests.BODIES.getOrDefault(endpoint, method.equals("GET") || method.equals("DELETE") ? null : "{}");

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
