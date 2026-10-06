package dev.storeforge.stores;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditLogIT extends IntegrationTest {

    @Test
    void auditLogIsNewestFirstAndCursorPaginatedWithoutGapsOrDuplicates() {
        var owner = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        String base = "/admin/v1/stores/" + owner.storeId();
        for (int i = 1; i <= 3; i++) {
            api.patch(base, Json.object("name", "Name " + i), owner.auth());
        }

        var first = Json.parse(api.get(base + "/audit-log?limit=2", owner.auth()).body());
        String cursor = first.get("page").get("nextCursor").asString();
        var second = Json.parse(api.get(base + "/audit-log?limit=2&cursor=" + cursor, owner.auth()).body());

        List<String> actions = new ArrayList<>();
        first.get("data").valueStream().forEach(entry -> actions.add(entry.get("action").asString() + ":" + entry.get("id").asString()));
        second.get("data").valueStream().forEach(entry -> actions.add(entry.get("action").asString() + ":" + entry.get("id").asString()));

        assertThat(first.get("page").get("hasMore").asBoolean()).isTrue();
        assertThat(second.get("page").get("hasMore").asBoolean()).isFalse();
        assertThat(actions).hasSize(4).doesNotHaveDuplicates();
        assertThat(actions.getFirst()).startsWith("store.renamed");
        assertThat(actions.getLast()).startsWith("store.created");
        assertThat(first.get("data").get(0).get("details").get("to").asString()).isEqualTo("Name 3");
    }

    @Test
    void malformedCursorIsABadRequest() {
        var owner = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());

        var response = api.get("/admin/v1/stores/" + owner.storeId() + "/audit-log?cursor=bm9wZQ", owner.auth());   // base64url of "nope"

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.parse(response.body()).get("code").asString()).isEqualTo("invalid_cursor");
    }
}
