package dev.storeforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows.Session;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

/** HTTP-level store/staff steps for integration tests. */
public final class StoreFlows {

    private final HttpTestClient api;
    private final RecordingEmailSender mailbox;
    private final AuthFlows auth;

    public StoreFlows(HttpTestClient api, RecordingEmailSender mailbox) {
        this.api = api;
        this.mailbox = mailbox;
        this.auth = new AuthFlows(api, mailbox);
    }

    /** A user (fresh account) who owns a fresh store. */
    public record Owner(String email, Session session, UUID storeId) {
        public Map<String, String> auth() {
            return bearer(session.accessToken());
        }
    }

    public static Map<String, String> bearer(String accessToken) {
        return Map.of("Authorization", "Bearer " + accessToken);
    }

    public static String uniqueHandle() {
        return "store-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public Owner newOwnerWithStore(String email) {
        Session session = auth.newVerifiedSession(email);
        HttpResponse<String> created = createStore(session.accessToken(), uniqueHandle());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return new Owner(email, session, UUID.fromString(Json.parse(created.body()).get("id").asString()));
    }

    public HttpResponse<String> createStore(String accessToken, String handle) {
        return api.post("/admin/v1/stores", Json.object("handle", handle, "name", "Shop " + handle, "currency", "USD"),
                bearer(accessToken));
    }

    public UUID roleId(Owner owner, String roleName) {
        var roles = Json.parse(api.get("/admin/v1/stores/" + owner.storeId() + "/roles", owner.auth()).body()).get("data");
        return roles.valueStream().filter(role -> role.get("name").asString().equals(roleName))
                .map(role -> UUID.fromString(role.get("id").asString())).findFirst().orElseThrow();
    }

    public UUID createRole(Owner owner, String name, String... permissions) {
        var body = new StringBuilder("{\"name\":\"").append(name).append("\",\"permissions\":[");
        for (int i = 0; i < permissions.length; i++) {
            body.append(i == 0 ? "" : ",").append('"').append(permissions[i]).append('"');
        }
        body.append("]}");
        HttpResponse<String> response = api.post("/admin/v1/stores/" + owner.storeId() + "/roles", body.toString(), owner.auth());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(Json.parse(response.body()).get("id").asString());
    }

    /** Invites {@code email} into {@code roleId}, then that person registers, signs in and accepts. Returns their session. */
    public Session addMember(Owner owner, String email, UUID roleId) {
        HttpResponse<String> invited = api.post("/admin/v1/stores/" + owner.storeId() + "/invitations",
                Json.object("email", email, "roleId", roleId.toString()), owner.auth());
        assertThat(invited.statusCode()).as(invited.body()).isEqualTo(201);
        String token = mailbox.latestToken(email, "invited").orElseThrow();
        Session session = auth.newVerifiedSession(email);
        HttpResponse<String> accepted = acceptInvitation(session.accessToken(), owner.storeId(), token);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(204);
        return session;
    }

    public HttpResponse<String> acceptInvitation(String accessToken, UUID storeId, String token) {
        return api.post("/admin/v1/stores/" + storeId + "/invitations/acceptance", Json.object("token", token), bearer(accessToken));
    }

    public UUID userId(Session session) {
        return UUID.fromString(Json.parse(auth.me(session.accessToken()).body()).get("id").asString());
    }
}
