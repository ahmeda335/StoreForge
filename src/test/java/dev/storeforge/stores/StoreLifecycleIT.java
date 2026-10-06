package dev.storeforge.stores;

import static dev.storeforge.support.StoreFlows.bearer;
import static dev.storeforge.support.StoreFlows.uniqueHandle;
import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreLifecycleIT extends IntegrationTest {

    AuthFlows auth;
    StoreFlows stores;

    @BeforeEach
    void flows() {
        auth = new AuthFlows(api, mailbox);
        stores = new StoreFlows(api, mailbox);
    }

    @Test
    void creatingAStoreMakesTheCreatorItsOwner() {
        var session = auth.newVerifiedSession(uniqueEmail());
        String handle = uniqueHandle().toUpperCase();

        HttpResponse<String> created = stores.createStore(session.accessToken(), handle);

        assertThat(created.statusCode()).isEqualTo(201);
        var store = Json.parse(created.body());
        String storeId = store.get("id").asString();
        assertThat(created.headers().firstValue("Location")).hasValue("/admin/v1/stores/" + storeId);
        assertThat(store.get("handle").asString()).isEqualTo(handle.toLowerCase());
        assertThat(store.get("status").asString()).isEqualTo("ACTIVE");

        var mine = Json.parse(api.get("/admin/v1/stores", bearer(session.accessToken())).body()).get("data");
        assertThat(mine.valueStream().map(s -> s.get("id").asString())).containsExactly(storeId);

        var members = Json.parse(api.get("/admin/v1/stores/" + storeId + "/members", bearer(session.accessToken())).body()).get("data");
        assertThat(members.size()).isEqualTo(1);
        assertThat(members.get(0).get("owner").asBoolean()).isTrue();
        assertThat(members.get(0).get("roleName").asString()).isEqualTo("Owner");

        var roles = Json.parse(api.get("/admin/v1/stores/" + storeId + "/roles", bearer(session.accessToken())).body()).get("data");
        assertThat(roles.valueStream().map(r -> r.get("name").asString())).containsExactly("Owner", "Staff");
    }

    @Test
    void handlesAreUniqueCaseInsensitivelyAndReservedOnesAreUnavailable() {
        var session = auth.newVerifiedSession(uniqueEmail());
        String handle = uniqueHandle();
        stores.createStore(session.accessToken(), handle);

        HttpResponse<String> duplicate = stores.createStore(session.accessToken(), handle.toUpperCase());
        HttpResponse<String> reserved = stores.createStore(session.accessToken(), "admin");
        HttpResponse<String> malformed = stores.createStore(session.accessToken(), "-bad handle-");

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(code(duplicate)).isEqualTo("handle_unavailable");
        assertThat(code(reserved)).isEqualTo("handle_unavailable");
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(code(malformed)).isEqualTo("invalid_handle");
    }

    @Test
    void renamingIsAudited() {
        var owner = stores.newOwnerWithStore(uniqueEmail());

        HttpResponse<String> renamed = api.patch("/admin/v1/stores/" + owner.storeId(), Json.object("name", "Renamed Shop"), owner.auth());

        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(Json.parse(renamed.body()).get("name").asString()).isEqualTo("Renamed Shop");
        var audit = Json.parse(api.get("/admin/v1/stores/" + owner.storeId() + "/audit-log", owner.auth()).body()).get("data");
        assertThat(audit.get(0).get("action").asString()).isEqualTo("store.renamed");
        assertThat(audit.get(0).get("details").get("to").asString()).isEqualTo("Renamed Shop");
        assertThat(audit.get(0).get("requestId").asString()).isEqualTo(renamed.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @Test
    void storeEndpointsRequireAuthentication() {
        assertThat(api.get("/admin/v1/stores").statusCode()).isEqualTo(401);
    }

    private static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
