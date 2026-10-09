package dev.storeforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.storeforge.shared.web.SecurityHeadersFilter;
import dev.storeforge.support.HttpTestClient;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.io.ByteArrayInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The application's edge: headers, body limits, CORS, and which forwarded headers are believed (OWASP API4, API8). */
class HttpEdgeIT extends IntegrationTest {

    @Test
    void jsonResponsesForbidRenderingFramingAndReferrers() {
        HttpResponse<String> response = api.get("/storefront/v1/products", Map.of("X-Forwarded-Host", "nobody.storeforge.localhost"));
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue(SecurityHeadersFilter.API_POLICY);
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    /** The login page runs no script; its only inline style is allowed by a hash that must match the style it serves. */
    @Test
    void htmlPagesAllowExactlyTheirOwnStyleAndNothingElse() throws Exception {
        HttpResponse<String> page = api.get("/oauth/login");
        String policy = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(policy).startsWith("default-src 'none'; style-src 'sha256-").doesNotContain("script-src", "unsafe");
        Matcher style = Pattern.compile("<style>(.*?)</style>", Pattern.DOTALL).matcher(page.body());
        assertThat(style.find()).isTrue();
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(style.group(1).getBytes(StandardCharsets.UTF_8)));
        assertThat(policy).contains("'sha256-" + hash + "'");
    }

    @Test
    void bodiesOverTheLimitAreRefusedBeforeAnythingParsesThem() throws Exception {
        String big = "{\"email\":\"" + "a".repeat(2_000_000) + "@example.test\",\"password\":\"x\"}";
        HttpResponse<String> declared = api.post("/auth/v1/token", big);
        assertThat(declared.statusCode()).isEqualTo(413);
        assertThat(Json.parse(declared.body()).get("code").asString()).isEqualTo("payload_too_large");

        // Without Content-Length (chunked): counted while reading.
        HttpResponse<String> chunked = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/auth/v1/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big.getBytes(StandardCharsets.UTF_8))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(chunked.statusCode()).isEqualTo(413);
        assertThat(Json.parse(chunked.body()).get("code").asString()).isEqualTo("payload_too_large");
    }

    @Test
    void onlyTheAdminFrontendMayCallTheAdminApiFromABrowser() {
        HttpResponse<String> allowed = preflight("/admin/v1/stores", "http://localhost:4200", "POST");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:4200");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");

        HttpResponse<String> evil = preflight("/admin/v1/stores", "https://evil.example", "POST");
        assertThat(evil.statusCode()).isEqualTo(403);
        assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(Json.parse(evil.body()).get("code").asString()).as("same error format as everything else").isEqualTo("cross_site_request_rejected");

        HttpResponse<String> storefront = preflight("/storefront/v1/carts", "https://any-shop.example", "POST");
        assertThat(storefront.headers().firstValue("Access-Control-Allow-Origin")).as("public API: any origin").isPresent();
        assertThat(storefront.headers().firstValue("Access-Control-Allow-Credentials")).as("but never with credentials").isEmpty();

        HttpResponse<String> developer = preflight("/api/2026-10/stores/x/orders", "https://any-shop.example", "GET");
        assertThat(developer.headers().firstValue("Access-Control-Allow-Origin")).as("server-to-server API: no CORS").isEmpty();
    }

    /**
     * A client that is NOT a trusted proxy can't choose its address or host through X-Forwarded-*.
     * Seen from 127.0.0.1 (trusted in tests, like a local proxy) the header selects the store; seen
     * from the machine's LAN address, the same header is ignored.
     */
    @Test
    void forwardedHeadersAreBelievedOnlyFromTrustedProxies() {
        Owner shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        Map<String, String> viaHost = new StorefrontFlows(api, shop).headers(null);
        assertThat(api.get("/storefront/v1/products", viaHost).statusCode()).as("from the trusted proxy").isEqualTo(200);

        Optional<String> lan = nonLoopbackAddress();
        assumeTrue(lan.isPresent(), "needs a non-loopback IPv4 address to connect from");
        HttpTestClient untrusted = new HttpTestClient("http://" + lan.get() + ":" + environment.getProperty("local.server.port"));
        HttpResponse<String> spoofed = untrusted.get("/storefront/v1/products", viaHost);
        assertThat(spoofed.statusCode()).as("X-Forwarded-Host ignored").isEqualTo(404);
        assertThat(Json.parse(spoofed.body()).get("code").asString()).isEqualTo("store_not_found");
    }

    private HttpResponse<String> preflight(String path, String origin, String method) {
        return api.request("OPTIONS", path, null, Map.of("Origin", origin, "Access-Control-Request-Method", method,
                "Access-Control-Request-Headers", "content-type"));
    }

    private String base() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    static Optional<String> nonLoopbackAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        return Optional.of(address.getHostAddress());
                    }
                }
            }
        } catch (java.net.SocketException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
