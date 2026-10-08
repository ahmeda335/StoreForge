package dev.storeforge.developer;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.DeveloperFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.OAuthBrowser;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Third-party app installation over OAuth 2.0 authorization code + PKCE, driven like a real browser
 * and a real app backend. Each hop is asserted, and each classic attack has its test.
 */
class OAuthInstallIT extends IntegrationTest {

    static final String REDIRECT = "http://localhost:9/oauth/callback";

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    Owner shop;
    String storeHost;
    App app;
    OAuthBrowser browser;

    record App(String clientId, String secret) {
    }

    record Install(String code, String verifier) {
    }

    @BeforeEach
    void appAndStore() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        shop = stores.newOwnerWithStore(uniqueEmail());
        storeHost = Json.parse(api.get("/admin/v1/stores/" + shop.storeId(), shop.auth()).body()).get("handle").asString() + ".storeforge.localhost";
        Owner developer = stores.newOwnerWithStore(uniqueEmail());
        HttpResponse<String> registered = api.post("/admin/v1/apps", """
                {"name":"StockSync","redirectUris":["%s"],"scopes":["read_orders","read_inventory","write_inventory"]}""".formatted(REDIRECT),
                developer.auth());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        JsonNode body = Json.parse(registered.body());
        app = new App(body.get("clientId").asString(), body.get("clientSecret").asString());
        browser = new OAuthBrowser("http://localhost:" + environment.getProperty("local.server.port"));
    }

    @Test
    void aMerchantInstallsAnAppAndTheAppCallsTheApiOnTheirStore() {
        Install install = authorize(shop.email(), "read_orders read_inventory", true);

        HttpResponse<String> token = exchange(install.code(), install.verifier());

        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        JsonNode tokens = Json.parse(token.body());
        assertThat(tokens.get("access_token").asString()).startsWith("sfat_");
        assertThat(tokens.get("store_id").asString()).isEqualTo(shop.storeId().toString());
        assertThat(tokens.get("scope").asString().split(" ")).containsExactlyInAnyOrder("read_orders", "read_inventory");
        assertThat(tokens.has("refresh_token")).as("offline installation token").isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE access_token_value IS NOT NULL").query(Integer.class).single())
                .as("issued tokens are never stored in plaintext (only their hash, in api_credentials)").isZero();
        Map<String, String> appAuth = Map.of("Authorization", "Bearer " + tokens.get("access_token").asString());
        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/orders"), appAuth).statusCode()).isEqualTo(200);
        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/products"), appAuth).statusCode()).as("not granted").isEqualTo(403);

        JsonNode installed = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/app-installations", shop.auth()).body()).get("data");
        assertThat(installed).hasSize(1);
        assertThat(installed.get(0).get("appName").asString()).isEqualTo("StockSync");
    }

    @Test
    void uninstallingRevokesTheTokenAtOnceAndReinstallingReplacesIt() {
        Install first = authorize(shop.email(), "read_orders read_inventory", true);
        String firstToken = Json.parse(exchange(first.code(), first.verifier()).body()).get("access_token").asString();
        Map<String, String> firstAuth = Map.of("Authorization", "Bearer " + firstToken);
        HttpResponse<String> subscribed = api.post(DeveloperFlows.path(shop.storeId(), "/webhook-subscriptions"),
                "{\"url\":\"https://example.com/stocksync\",\"topics\":[\"inventory_levels/update\"]}", firstAuth);
        assertThat(subscribed.statusCode()).as(subscribed.body()).isEqualTo(201);

        Install again = authorize(shop.email(), "read_orders read_inventory", true);
        String secondToken = Json.parse(exchange(again.code(), again.verifier()).body()).get("access_token").asString();
        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/orders"), firstAuth).statusCode()).as("replaced").isEqualTo(401);
        Map<String, String> secondAuth = Map.of("Authorization", "Bearer " + secondToken);
        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/orders"), secondAuth).statusCode()).isEqualTo(200);

        String installationId = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/app-installations", shop.auth()).body())
                .get("data").get(0).get("id").asString();
        assertThat(api.delete("/admin/v1/stores/" + shop.storeId() + "/app-installations/" + installationId, shop.auth()).statusCode())
                .isEqualTo(204);

        assertThat(api.get(DeveloperFlows.path(shop.storeId(), "/orders"), secondAuth).statusCode()).as("uninstalled").isEqualTo(401);
        assertThat(Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/webhook-subscriptions", shop.auth()).body()).get("data"))
                .as("the app's subscriptions are gone").isEmpty();
    }

    /** The consent page hides the button, but the form can be posted by hand: token issuance re-checks. */
    @Test
    void aMerchantWhoMayNotGrantTheScopesCannotInstallEvenByForgingTheConsent() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        String staffEmail = uniqueEmail();
        stores.addMember(shop, staffEmail, stores.roleId(shop, "Staff"));   // no apps:manage

        Install forged = authorize(staffEmail, "read_orders", false);
        HttpResponse<String> token = exchange(forged.code(), forged.verifier());

        assertThat(token.statusCode()).isEqualTo(400);
        assertThat(Json.parse(token.body()).get("error").asString()).isEqualTo("invalid_grant");
        assertThat(Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/app-installations", shop.auth()).body()).get("data")).isEmpty();
    }

    /**
     * Anti-escalation through OAuth: a merchant who MAY install apps, but lacks inventory permissions,
     * must not hand an app write access to inventory (EXP-016: the first forging test didn't cover this).
     */
    @Test
    void anAppCannotBeGrantedMoreThanTheInstallingMerchantHolds() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        String integratorEmail = uniqueEmail();
        stores.addMember(shop, integratorEmail, stores.createRole(shop, "Integrator", "apps:manage", "orders:read"));

        assertThat(exchange(authorize(integratorEmail, "read_orders", true).code(), "x").statusCode()).as("sanity: needs verifier")
                .isEqualTo(400);
        Install allowed = authorize(integratorEmail, "read_orders", true);
        assertThat(exchange(allowed.code(), allowed.verifier()).statusCode()).isEqualTo(200);

        Install escalated = authorize(integratorEmail, "read_orders write_inventory", false);
        HttpResponse<String> token = exchange(escalated.code(), escalated.verifier());
        assertThat(token.statusCode()).isEqualTo(400);
        assertThat(Json.parse(token.body()).get("error").asString()).isEqualTo("invalid_grant");
    }

    @Test
    void codesAreSingleUseAndBoundToTheVerifier() {
        Install install = authorize(shop.email(), "read_orders", true);
        assertThat(Json.parse(exchange(install.code(), "wrong-" + install.verifier()).body()).get("error").asString())
                .as("PKCE: a stolen code is useless without the verifier").isEqualTo("invalid_grant");

        Install fresh = authorize(shop.email(), "read_orders", true);
        assertThat(exchange(fresh.code(), fresh.verifier()).statusCode()).isEqualTo(200);
        HttpResponse<String> replay = exchange(fresh.code(), fresh.verifier());
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(Json.parse(replay.body()).get("error").asString()).isEqualTo("invalid_grant");
        assertThat(exchangeWithSecret(fresh.code(), fresh.verifier(), "sfcs_wrong").statusCode()).isEqualTo(401);
    }

    @Test
    void theAuthorizationRequestIsValidatedBeforeAnyCodeIsIssued() {
        login(shop.email());
        String verifier = OAuthBrowser.newVerifier();

        HttpResponse<String> foreignRedirect = browser.get(authorizeUrl("read_orders", "https://attacker.example/steal", verifier, true));
        assertThat(foreignRedirect.statusCode()).as("never redirect to an unregistered URI").isEqualTo(400);
        assertThat(foreignRedirect.headers().firstValue("Location")).isEmpty();

        String noPkce = OAuthBrowser.location(browser.get(authorizeUrl("read_orders", REDIRECT, verifier, false)));
        assertThat(noPkce).startsWith(REDIRECT).contains("error=invalid_request");

        String tooMuch = OAuthBrowser.location(browser.get(authorizeUrl("read_orders write_products", REDIRECT, verifier, true)));
        assertThat(tooMuch).startsWith(REDIRECT).contains("error=invalid_scope");
    }

    /** The second login door (OAuth) has the same brute-force protection as the API login, and shares its lockout. */
    @Test
    void theOAuthLoginIsRateLimitedAndLocksOutLikeTheApiLogin() {
        String email = uniqueEmail();
        new AuthFlows(api, mailbox).registerAndVerify(email, AuthFlows.PASSWORD);
        browser.get(authorizeUrl("read_orders", REDIRECT, OAuthBrowser.newVerifier(), true));
        for (int i = 0; i < 5; i++) {
            assertThat(OAuthBrowser.location(submitLogin(email, "wrong password " + i))).contains("/oauth/login?error");
        }

        assertThat(OAuthBrowser.location(submitLogin(email, AuthFlows.PASSWORD))).as("locked out").contains("/oauth/login?error");
        HttpResponse<String> apiLogin = api.post("/auth/v1/token", Json.object("email", email, "password", AuthFlows.PASSWORD));
        // Refused by the shared protections: the per-email rate limit (429) fires before the lockout check (401).
        assertThat(apiLogin.statusCode()).as("the same account is protected for the API login too").isIn(401, 429);
    }

    /** The authorization server's browser session must never authenticate the admin or developer APIs. */
    @Test
    void theOAuthBrowserSessionCannotCallTheApis() {
        login(shop.email());
        assertThat(browser.get("/admin/v1/stores/" + shop.storeId()).statusCode()).isEqualTo(401);
        assertThat(browser.get(DeveloperFlows.path(shop.storeId(), "/orders")).statusCode()).isEqualTo(401);
    }

    // ---- the flow --------------------------------------------------------------------------------

    /** Browser: authorize → login → consent → approve; returns the code and the PKCE verifier. */
    private Install authorize(String email, String scope, boolean expectInstallButton) {
        String verifier = OAuthBrowser.newVerifier();
        HttpResponse<String> start = browser.get(authorizeUrl(scope, REDIRECT, verifier, true));
        if (OAuthBrowser.location(start).contains("/oauth/login")) {
            HttpResponse<String> loggedIn = submitLogin(email, AuthFlows.PASSWORD);
            start = browser.get(OAuthBrowser.location(loggedIn));
        }
        String consentUrl = OAuthBrowser.location(start);
        assertThat(consentUrl).contains("/oauth/consent");
        HttpResponse<String> consent = browser.get(consentUrl);
        assertThat(consent.body()).contains("StockSync").contains(storeHost.substring(0, storeHost.indexOf('.')));
        assertThat(consent.body().contains("Install app")).isEqualTo(expectInstallButton);
        String state = OAuthBrowser.query(consentUrl, "state");
        List<Map.Entry<String, String>> form = new ArrayList<>(List.of(entry("client_id", app.clientId()), entry("state", state)));
        for (String s : scope.split(" ")) {
            form.add(entry("scope", s));
        }
        String callback = OAuthBrowser.location(browser.post("/oauth2/authorize", form, Map.of()));
        assertThat(callback).startsWith(REDIRECT).contains("state=app-state");
        return new Install(OAuthBrowser.query(callback, "code"), verifier);
    }

    private void login(String email) {
        browser.get(authorizeUrl("read_orders", REDIRECT, OAuthBrowser.newVerifier(), true));
        submitLogin(email, AuthFlows.PASSWORD);
    }

    private HttpResponse<String> submitLogin(String email, String password) {
        String csrf = OAuthBrowser.hidden(browser.get("/oauth/login").body(), "_csrf");
        return browser.post("/oauth/login", List.of(entry("username", email), entry("password", password), entry("_csrf", csrf)), Map.of());
    }

    private String authorizeUrl(String scope, String redirect, String verifier, boolean pkce) {
        return "/oauth2/authorize?response_type=code&client_id=" + OAuthBrowser.encode(app.clientId())
                + "&redirect_uri=" + OAuthBrowser.encode(redirect) + "&scope=" + OAuthBrowser.encode(scope) + "&state=app-state"
                + "&store=" + OAuthBrowser.encode(storeHost)
                + (pkce ? "&code_challenge=" + OAuthBrowser.challenge(verifier) + "&code_challenge_method=S256" : "");
    }

    /** The app's backend: code + verifier + client credentials → access token. */
    private HttpResponse<String> exchange(String code, String verifier) {
        return exchangeWithSecret(code, verifier, app.secret());
    }

    private HttpResponse<String> exchangeWithSecret(String code, String verifier, String secret) {
        return new OAuthBrowser("http://localhost:" + environment.getProperty("local.server.port")).post("/oauth2/token",
                List.of(entry("grant_type", "authorization_code"), entry("code", code), entry("redirect_uri", REDIRECT),
                        entry("code_verifier", verifier)),
                Map.of("Authorization", OAuthBrowser.basic(app.clientId(), secret)));
    }
}
