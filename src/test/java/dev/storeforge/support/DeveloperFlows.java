package dev.storeforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Merchant creates API keys; integrations call /api/2026-10 with them. */
public final class DeveloperFlows {

    public static final String VERSION = "2026-10";

    private final HttpTestClient api;

    public DeveloperFlows(HttpTestClient api) {
        this.api = api;
    }

    public record Key(UUID id, String token) {
        public Map<String, String> auth() {
            return Map.of("Authorization", "Bearer " + token);
        }
    }

    public Key createKey(Owner owner, String... scopes) {
        HttpResponse<String> created = createKey(owner.auth(), owner.storeId(), scopes);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode body = Json.parse(created.body());
        return new Key(UUID.fromString(body.get("id").asString()), body.get("token").asString());
    }

    public HttpResponse<String> createKey(Map<String, String> auth, UUID storeId, String... scopes) {
        return api.post("/admin/v1/stores/" + storeId + "/api-keys", "{\"name\":\"Integration\",\"scopes\":" + Json.array((Object[]) scopes) + "}",
                auth);
    }

    public static String path(UUID storeId, String resource) {
        return "/api/" + VERSION + "/stores/" + storeId + resource;
    }
}
