package dev.storeforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.AuthFlows.Session;
import dev.storeforge.support.DeveloperFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.PostgresTestDatabase;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import dev.storeforge.support.TestAuthenticator;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Platform administration: password + mandatory TOTP, its own token audience, every action audited (OWASP API2, API5). */
class PlatformAdminIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    String email;
    Session merchantSession;
    UUID userId;

    @BeforeEach
    void aUserWhoWillBecomeAnAdmin() {
        email = uniqueEmail();
        merchantSession = new AuthFlows(api, mailbox).newVerifiedSession(email);
        userId = UUID.fromString(Json.parse(new AuthFlows(api, mailbox).me(merchantSession.accessToken()).body()).get("id").asString());
    }

    @Test
    void stepUpRequiresAnEnrolledSecondFactorAndEachCodeWorksOnce() {
        assertThat(post("/platform/v1/mfa/enrollment", "{}", merchant()).statusCode()).as("not an admin: as if it didn't exist").isEqualTo(404);
        grantAdmin();

        assertThat(code(post("/platform/v1/session", "{\"code\":\"123456\"}", merchant()))).isEqualTo("mfa_not_enrolled");
        JsonNode enrollment = Json.parse(post("/platform/v1/mfa/enrollment", "{}", merchant()).body());
        assertThat(enrollment.get("otpauthUri").asString()).startsWith("otpauth://totp/StoreForge:").contains("secret=" + enrollment.get("secret").asString());
        TestAuthenticator phone = new TestAuthenticator(enrollment.get("secret").asString());

        assertThat(code(post("/platform/v1/mfa/enrollment/confirmation", "{\"code\":\"000000\"}", merchant()))).isEqualTo("invalid_mfa_code");
        HttpResponse<String> confirmed = post("/platform/v1/mfa/enrollment/confirmation", "{\"code\":\"" + phone.code(0) + "\"}", merchant());
        assertThat(Json.parse(confirmed.body()).get("recoveryCodes")).hasSize(10);
        assertThat(confirmed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(code(post("/platform/v1/mfa/enrollment", "{}", merchant()))).isEqualTo("mfa_already_enrolled");

        String next = phone.code(1);
        HttpResponse<String> session = post("/platform/v1/session", "{\"code\":\"" + next + "\"}", merchant());
        assertThat(session.statusCode()).as(session.body()).isEqualTo(200);
        assertThat(Json.parse(session.body()).get("amr").valueStream().map(JsonNode::asString)).containsExactly("pwd", "otp");
        assertThat(code(post("/platform/v1/session", "{\"code\":\"" + next + "\"}", merchant()))).as("replay of the same code").isEqualTo("invalid_mfa_code");
        assertThat(code(post("/platform/v1/session", "{\"code\":\"" + phone.code(0) + "\"}", merchant())))
                .as("an older step after a newer one").isEqualTo("invalid_mfa_code");
    }

    @Test
    void merchantAndPlatformTokensAreNotInterchangeable() {
        String platform = platformToken();
        assertThat(api.get("/platform/v1/stores", merchant()).statusCode()).as("password alone").isEqualTo(401);
        assertThat(api.get("/platform/v1/stores", bearer(platform)).statusCode()).isEqualTo(200);
        assertThat(api.get("/admin/v1/stores", bearer(platform)).statusCode()).as("platform token on the merchant API").isEqualTo(401);
        assertThat(api.get("/platform/v1/stores", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void aSuspendedStoreGoesOfflineAndItsMerchantSeesWhy() {
        Owner shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        var key = new DeveloperFlows(api).createKey(shop, "read_orders");
        StorefrontFlows storefront = new StorefrontFlows(api, shop);
        String platform = platformToken();

        HttpResponse<String> suspended = post("/platform/v1/stores/" + shop.storeId() + "/suspension", "{\"reason\":\"Card testing from this store\"}",
                bearer(platform));
        assertThat(Json.parse(suspended.body()).get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(api.get("/storefront/v1/products", storefront.headers(null)).statusCode()).isEqualTo(404);
        assertThat(api.get("/api/2026-10/stores/" + shop.storeId() + "/orders", key.auth()).statusCode()).isEqualTo(403);
        assertThat(code(api.get("/admin/v1/stores/" + shop.storeId() + "/audit-log", shop.auth()))).as("the merchant is told why")
                .isEqualTo("store_suspended");
        assertThat(code(post("/platform/v1/stores/" + shop.storeId() + "/suspension", "{\"reason\":\"again\"}", bearer(platform))))
                .isEqualTo("store_status_conflict");

        post("/platform/v1/stores/" + shop.storeId() + "/reinstatement", "{\"reason\":\"Resolved with the merchant\"}", bearer(platform));
        assertThat(api.get("/storefront/v1/products", storefront.headers(null)).statusCode()).isEqualTo(200);
        JsonNode merchantAudit = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/audit-log", shop.auth()).body()).get("data");
        assertThat(merchantAudit.valueStream().filter(e -> e.get("actorType").asString().equals("ADMIN")).map(e -> e.get("action").asString()))
                .as("the merchant's own audit log shows what the platform did").containsExactly("store.reinstated", "store.suspended");
        assertThat(merchantAudit.get(1).get("details").get("reason").asString()).isEqualTo("Card testing from this store");

        List<String> actions = Json.parse(api.get("/platform/v1/audit?limit=200", bearer(platform)).body()).get("data").valueStream()
                .filter(e -> e.get("adminUserId").asString().equals(userId.toString())).map(e -> e.get("action").asString()).toList();
        assertThat(actions).contains("store.reinstated", "store.suspended", "platform.session_started", "platform.mfa_enrolled");
    }

    @Test
    void aRecoveryCodeWorksExactlyOnce() {
        grantAdmin();
        TestAuthenticator phone = enroll();
        List<String> recovery = confirm(phone);
        assertThat(post("/platform/v1/session", "{\"recoveryCode\":\"" + recovery.get(3).toUpperCase() + "\"}", merchant()).statusCode())
                .as("case and dash don't matter").isEqualTo(200);
        assertThat(code(post("/platform/v1/session", "{\"recoveryCode\":\"" + recovery.get(3) + "\"}", merchant()))).isEqualTo("invalid_mfa_code");
        assertThat(post("/platform/v1/session", "{\"code\":\"" + phone.code(1) + "\",\"recoveryCode\":\"" + recovery.get(4) + "\"}", merchant())
                .statusCode()).as("exactly one factor per request").isEqualTo(400);
    }

    @Test
    void revokingAnAdminCutsOffTheirTokenAtOnce() {
        String platform = platformToken();
        assertThat(api.get("/platform/v1/stores", bearer(platform)).statusCode()).isEqualTo(200);
        PostgresTestDatabase.executeAsOwner("DELETE FROM platform_admins WHERE user_id = ?", userId);
        assertThat(api.get("/platform/v1/stores", bearer(platform)).statusCode()).isEqualTo(401);
    }

    /** 10^6 codes, ~3 valid at any time: unlimited guessing would succeed in hours. 5 wrong codes per 15 minutes. */
    @Test
    void guessingCodesIsCutOffEvenBeforeTheRightOne() {
        grantAdmin();
        TestAuthenticator phone = enroll();
        confirm(phone);
        assertThat(post("/platform/v1/session", "{\"code\":\"" + phone.code(1) + "\"}", merchant()).statusCode())
                .as("successes don't count").isEqualTo(200);
        for (int i = 0; i < 5; i++) {
            assertThat(code(post("/platform/v1/session", "{\"code\":\"00000" + i + "\"}", merchant()))).isEqualTo("invalid_mfa_code");
        }
        HttpResponse<String> right = post("/platform/v1/session", "{\"code\":\"" + phone.code(1) + "\"}", merchant());
        assertThat(right.statusCode()).as("after 5 wrong codes, even the right one waits").isEqualTo(429);
    }

    /** Defence in depth: even a SQL injection in the application could not create a platform admin or rewrite history. */
    @Test
    void theApplicationRoleCanNeitherGrantAdminRightsNorRewriteThePlatformAuditLog() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO platform_admins (user_id) VALUES (:user)").param("user", userId).update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("UPDATE platform_audit_log SET action = 'nothing'").update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM platform_audit_log").update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private String platformToken() {
        grantAdmin();
        TestAuthenticator phone = enroll();
        confirm(phone);
        HttpResponse<String> session = post("/platform/v1/session", "{\"code\":\"" + phone.code(1) + "\"}", merchant());
        assertThat(session.statusCode()).as(session.body()).isEqualTo(200);
        return Json.parse(session.body()).get("access_token").asString();
    }

    private void grantAdmin() {
        PostgresTestDatabase.executeAsOwner("INSERT INTO platform_admins (user_id, note) VALUES (?, 'test') ON CONFLICT DO NOTHING", userId);
    }

    private TestAuthenticator enroll() {
        return new TestAuthenticator(Json.parse(post("/platform/v1/mfa/enrollment", "{}", merchant()).body()).get("secret").asString());
    }

    private List<String> confirm(TestAuthenticator phone) {
        HttpResponse<String> confirmed = post("/platform/v1/mfa/enrollment/confirmation", "{\"code\":\"" + phone.code(0) + "\"}", merchant());
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        return Json.parse(confirmed.body()).get("recoveryCodes").valueStream().map(JsonNode::asString).toList();
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) {
        return api.post(path, body, headers);
    }

    private Map<String, String> merchant() {
        return bearer(merchantSession.accessToken());
    }

    private static Map<String, String> bearer(String token) {
        return StoreFlows.bearer(token);
    }

    private static String code(HttpResponse<String> response) {
        JsonNode body = Json.parse(response.body());
        return body.has("code") ? body.get("code").asString() : "HTTP " + response.statusCode();
    }
}
