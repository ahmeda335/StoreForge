package dev.storeforge.webhooks;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Outbound webhooks against a real HTTP receiver (WireMock) that verifies signatures like a merchant would. */
class WebhookDeliveryIT extends IntegrationTest {

    static WireMockServer receiver;

    @Autowired
    JobWorker worker;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantTransactions tenant;

    Owner shop;
    UUID variant;
    String path;

    @BeforeAll
    static void startReceiver() {
        receiver = new WireMockServer(options().dynamicPort());
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop();
    }

    @BeforeEach
    void shop() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        variant = new CatalogFlows(api).createVariant(shop, "Lamp");
        path = "/hooks/" + UUID.randomUUID();
        worker.drain();
    }

    record Subscription(UUID id, String secret) {
    }

    Subscription subscribe(String... topics) {
        HttpResponse<String> created = api.post(base(), "{\"url\":\"" + receiver.baseUrl() + path + "\",\"topics\":"
                + Json.array((Object[]) topics) + "}", shop.auth());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode body = Json.parse(created.body());
        return new Subscription(UUID.fromString(body.get("id").asString()), body.get("secret").asString());
    }

    void respond(ResponseDefinitionBuilder response) {
        receiver.stubFor(post(urlEqualTo(path)).willReturn(response));
    }

    void stockChanged(int delta) {
        assertThat(new CatalogFlows(api).adjustStock(shop, variant, delta, "RECEIVED").statusCode()).isEqualTo(200);
        worker.drain();
    }

    List<LoggedRequest> received() {
        return receiver.findAll(postRequestedFor(urlEqualTo(path)));
    }

    @Test
    void subscriptionsAreValidatedAndTheSecretIsShownOnlyOnce() {
        Subscription subscription = subscribe("orders/create", "inventory_levels/update");
        assertThat(subscription.secret()).startsWith("whsec_");
        JsonNode read = Json.parse(api.get(base() + "/" + subscription.id(), shop.auth()).body());
        assertThat(read.get("secret").isNull()).isTrue();
        assertThat(read.get("topics").valueStream().map(JsonNode::asString)).containsExactly("inventory_levels/update", "orders/create");

        assertThat(code(api.post(base(), "{\"url\":\"https://example.com/h\",\"topics\":[\"orders/explode\"]}", shop.auth())))
                .isEqualTo("unknown_topic");
        for (String url : List.of("http://169.254.169.254/latest/meta-data/", "http://10.0.0.7/hook", "http://[::ffff:10.0.0.7]/x",
                "http://user:pw@127.0.0.1/x")) {
            HttpResponse<String> refused = api.post(base(), "{\"url\":\"" + url + "\",\"topics\":[\"orders/create\"]}", shop.auth());
            assertThat(refused.statusCode()).as(url).isEqualTo(422);
            assertThat(code(refused)).isEqualTo("webhook_url_not_allowed");
        }
        StoreFlows stores = new StoreFlows(api, mailbox);
        var staff = stores.addMember(shop, uniqueEmail(), stores.roleId(shop, "Staff"));
        assertThat(api.get(base(), StoreFlows.bearer(staff.accessToken())).statusCode()).as("needs webhooks:manage").isEqualTo(403);
    }

    @Test
    void eventsAreDeliveredSignedWithTheEventIdAsTheReceiversDedupKey() throws Exception {
        respond(aResponse().withStatus(200).withBody("ok"));
        Subscription subscription = subscribe("orders/create", "orders/paid", "inventory_levels/update");

        stockChanged(5);
        var placed = new StorefrontFlows(api, shop).place(variant, 2, uniqueEmail());
        api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + placed.orderId() + "/manual-payment", "{}", shop.auth());
        worker.drain();

        List<LoggedRequest> requests = received();
        assertThat(requests).extracting(r -> r.getHeader("X-StoreForge-Topic"))
                .containsExactlyInAnyOrder("inventory_levels/update", "orders/create", "orders/paid", "inventory_levels/update");
        for (LoggedRequest request : requests) {
            assertThat(verify(request, List.of(subscription.secret()))).as("signature").isTrue();
            JsonNode body = Json.parse(request.getBodyAsString());
            assertThat(request.getHeader("X-StoreForge-Event-Id")).isEqualTo(body.get("id").asString());
            assertThat(request.getHeader("X-StoreForge-Store-Id")).isEqualTo(shop.storeId().toString());
            assertThat(request.getHeader("X-StoreForge-Api-Version")).isEqualTo("2026-10");
            assertThat(request.getHeader("User-Agent")).isEqualTo("StoreForge-Webhooks/1.0");
        }
        JsonNode paid = requests.stream().map(r -> Json.parse(r.getBodyAsString()))
                .filter(b -> b.get("topic").asString().equals("orders/paid")).findFirst().orElseThrow().get("data");
        assertThat(paid.get("orderId").asString()).isEqualTo(placed.orderId().toString());
        assertThat(paid.get("total").get("amount").asString()).isEqualTo("20.00");
        assertThat(paid.get("paymentMethod").asString()).isEqualTo("manual");
        JsonNode sold = requests.stream().map(r -> Json.parse(r.getBodyAsString()))
                .filter(b -> b.get("topic").asString().equals("inventory_levels/update") && b.get("data").get("delta").asInt() < 0)
                .findFirst().orElseThrow().get("data");
        assertThat(sold.get("onHand").asInt()).isEqualTo(3);

        JsonNode history = Json.parse(api.get(base() + "/" + subscription.id() + "/deliveries", shop.auth()).body()).get("data");
        assertThat(history.valueStream().map(d -> d.get("status").asString())).containsOnly("SUCCEEDED").hasSize(4);
        JsonNode one = Json.parse(api.get(base() + "/" + subscription.id() + "/deliveries/" + history.get(0).get("id").asString(),
                shop.auth()).body());
        assertThat(one.get("attempts").get(0).get("statusCode").asInt()).isEqualTo(200);
        assertThat(one.get("attempts").get(0).get("responseExcerpt").asString()).isEqualTo("ok");
    }

    /** The internal event is delivered at least once; the fan-out must not turn that into two webhooks. */
    @Test
    void aRedeliveredInternalEventDoesNotSendTheWebhookTwice() {
        respond(aResponse().withStatus(204));
        subscribe("inventory_levels/update");
        stockChanged(1);

        int reset = jdbc.sql("UPDATE jobs SET status = 'READY', run_at = now() WHERE type = 'event:webhooks' AND event_id = "
                        + "(SELECT id FROM outbox_events WHERE aggregate_type = 'inventory_item' AND store_id = :store ORDER BY occurred_at DESC LIMIT 1)")
                .param("store", shop.storeId()).update();
        assertThat(reset).isEqualTo(1);
        worker.drain();

        assertThat(received()).hasSize(1);
    }

    @Test
    void failuresAreRetriedOnTheScheduleUntilTheReceiverRecovers() {
        receiver.stubFor(post(urlEqualTo(path)).inScenario(path).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        receiver.stubFor(post(urlEqualTo(path)).inScenario(path).whenScenarioStateIs("recovered").willReturn(aResponse().withStatus(200)));
        Subscription subscription = subscribe("inventory_levels/update");

        stockChanged(1);
        JsonNode delivery = onlyDelivery(subscription);
        assertThat(delivery.get("status").asString()).isEqualTo("RETRYING");
        assertThat(delivery.get("lastStatusCode").asInt()).isEqualTo(503);
        assertThat(Duration.between(Instant.now(), Instant.parse(delivery.get("nextAttemptAt").asString())))
                .as("first retry after ~1 minute").isBetween(Duration.ofSeconds(45), Duration.ofSeconds(70));

        runDueAt(Instant.now().plus(Duration.ofMinutes(2)));

        assertThat(onlyDelivery(subscription).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(received()).hasSize(2);
        assertThat(received().get(0).getHeader("X-StoreForge-Event-Id")).isEqualTo(received().get(1).getHeader("X-StoreForge-Event-Id"));
        assertThat(received().get(0).getBodyAsString()).as("same bytes on every attempt").isEqualTo(received().get(1).getBodyAsString());
    }

    @Test
    void slowReceiversTimeOut() {
        Subscription subscription = subscribe("inventory_levels/update");
        respond(aResponse().withStatus(200).withFixedDelay(3_000));
        stockChanged(1);

        JsonNode attempt = attempts(subscription).get(0);
        assertThat(attempt.get("error").asString()).startsWith("timeout");
        assertThat(attempt.get("durationMs").asInt()).isLessThan(2_000);
        assertThat(onlyDelivery(subscription).get("status").asString()).isEqualTo("RETRYING");
    }

    /**
     * Status line at once, then a byte every 300 ms for 5 s: no single read ever waits 1 s, so a
     * per-read timeout never fires. Only the total deadline stops it. (WireMock's "dribble" delays
     * the status line too, which a per-read timeout already catches: an earlier version of this
     * test used it and passed without the deadline, EXP-013.)
     */
    @Test
    void drippingReceiversAreCutOffByTheTotalDeadline() throws Exception {
        Subscription subscription = subscribe("inventory_levels/update");
        try (ServerSocket dripping = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> drip(dripping));
            api.patch(base() + "/" + subscription.id(), "{\"url\":\"http://127.0.0.1:" + dripping.getLocalPort() + "/hook\"}", shop.auth());

            stockChanged(1);
        }

        JsonNode attempt = attempts(subscription).get(0);
        assertThat(attempt.get("error").asString()).startsWith("timeout");
        assertThat(attempt.get("durationMs").asInt()).as("bounded by the total deadline (1 s in tests)").isLessThan(2_000);
        assertThat(onlyDelivery(subscription).get("status").asString()).isEqualTo("RETRYING");
    }

    private static void drip(ServerSocket server) {
        try (Socket socket = server.accept()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // request headers; the body doesn't matter
            }
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            for (int i = 0; i < 16; i++) {
                Thread.sleep(300);
                out.write("1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            // the client hanging up on us is the expected outcome
        }
    }

    /** A redirect to the metadata service is the classic SSRF: it must be recorded, never followed. */
    @Test
    void redirectsAreNeverFollowed() {
        respond(aResponse().withStatus(302).withHeader("Location", "http://169.254.169.254/latest/meta-data/iam/"));
        Subscription subscription = subscribe("inventory_levels/update");
        stockChanged(1);

        JsonNode delivery = onlyDelivery(subscription);
        assertThat(delivery.get("lastStatusCode").asInt()).isEqualTo(302);
        assertThat(delivery.get("status").asString()).isEqualTo("RETRYING");
        assertThat(received()).hasSize(1);
    }

    @Test
    void clientErrorsDieAfterThreeAttemptsAndRetryAfterIsHonoured() {
        Subscription subscription = subscribe("inventory_levels/update");
        respond(aResponse().withStatus(429).withHeader("Retry-After", "600"));
        stockChanged(1);
        JsonNode limited = onlyDelivery(subscription);
        assertThat(Duration.between(Instant.now(), Instant.parse(limited.get("nextAttemptAt").asString())))
                .isBetween(Duration.ofMinutes(8), Duration.ofMinutes(12));

        respond(aResponse().withStatus(410));
        Instant now = Instant.now();
        runDueAt(now.plus(Duration.ofMinutes(15)));   // attempt 2 (410)
        runDueAt(now.plus(Duration.ofMinutes(25)));   // attempt 3 (410) → DEAD

        JsonNode dead = onlyDelivery(subscription);
        assertThat(dead.get("status").asString()).isEqualTo("DEAD");
        assertThat(dead.get("attempts").asInt()).isEqualTo(3);
    }

    @Test
    void deadDeliveriesCanBeReplayedWithTheSameEventId() {
        Subscription subscription = subscribe("inventory_levels/update");
        respond(aResponse().withStatus(400));
        stockChanged(1);
        UUID deliveryId = UUID.fromString(onlyDelivery(subscription).get("id").asString());
        String replayPath = base() + "/" + subscription.id() + "/deliveries/" + deliveryId + "/replay";
        assertThat(code(api.post(replayPath, "", shop.auth()))).as("still retrying").isEqualTo("webhook_delivery_in_progress");
        Instant now = Instant.now();
        runDueAt(now.plus(Duration.ofMinutes(2)));
        runDueAt(now.plus(Duration.ofMinutes(10)));
        assertThat(onlyDelivery(subscription).get("status").asString()).isEqualTo("DEAD");

        respond(aResponse().withStatus(200));
        assertThat(api.post(replayPath, "", shop.auth()).statusCode()).isEqualTo(202);
        worker.drain();

        JsonNode replayed = onlyDelivery(subscription);
        assertThat(replayed.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(replayed.get("replays").asInt()).isEqualTo(1);
        assertThat(received()).hasSize(4).extracting(r -> r.getHeader("X-StoreForge-Event-Id")).containsOnly(received().getFirst()
                .getHeader("X-StoreForge-Event-Id"));
    }

    /** One broken endpoint must not burn retries forever: pause it, tell the merchant, keep the events for replay. */
    @Test
    void aFailingEndpointIsPausedTheMerchantIsToldAndNothingIsLost() {
        Subscription subscription = subscribe("inventory_levels/update");
        respond(aResponse().withStatus(500));
        for (int i = 0; i < 5; i++) {
            stockChanged(1);
        }
        assertThat(subscriptionStatus(subscription)).isEqualTo("PAUSED_FAILING");
        assertThat(mailbox.sentTo(shop.email())).extracting(m -> m.subject()).anyMatch(s -> s.startsWith("Webhook endpoint paused"));

        stockChanged(1);   // while paused: kept, not sent
        runDueAt(Instant.now().plus(Duration.ofHours(1)));   // pending retries park instead of knocking
        assertThat(received()).hasSize(5);
        JsonNode paused = Json.parse(api.get(base() + "/" + subscription.id() + "/deliveries?status=PAUSED", shop.auth()).body()).get("data");
        assertThat(paused).hasSize(6);

        respond(aResponse().withStatus(200));
        api.patch(base() + "/" + subscription.id(), "{\"status\":\"ACTIVE\"}", shop.auth());
        HttpResponse<String> replay = api.post(base() + "/" + subscription.id() + "/replays",
                "{\"statuses\":[\"PAUSED\"],\"since\":\"" + Instant.now().minus(Duration.ofHours(1)) + "\"}", shop.auth());
        assertThat(Json.parse(replay.body()).get("queued").asInt()).isEqualTo(6);
        worker.drain();

        assertThat(Json.parse(api.get(base() + "/" + subscription.id() + "/deliveries?status=SUCCEEDED", shop.auth()).body()).get("data"))
                .hasSize(6);
        assertThat(subscriptionStatus(subscription)).isEqualTo("ACTIVE");
    }

    /** The receiver can switch secrets without losing a delivery: both sign during the overlap. */
    @Test
    void secretRotationSignsWithBothSecretsDuringTheOverlap() throws Exception {
        respond(aResponse().withStatus(200));
        Subscription subscription = subscribe("inventory_levels/update");
        String rotated = Json.parse(api.post(base() + "/" + subscription.id() + "/secret-rotation", "", shop.auth()).body())
                .get("secret").asString();

        stockChanged(1);
        LoggedRequest during = received().getLast();
        assertThat(during.getHeader("X-StoreForge-Signature").split(",")).hasSize(2);
        assertThat(verify(during, List.of(subscription.secret()))).as("old secret still accepted").isTrue();
        assertThat(verify(during, List.of(rotated))).as("new secret works").isTrue();

        tenant.runInStore(StoreId.of(shop.storeId()), () -> jdbc.sql(
                "UPDATE webhook_subscriptions SET previous_secret_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", subscription.id()).update());
        stockChanged(1);
        LoggedRequest after = received().getLast();
        assertThat(after.getHeader("X-StoreForge-Signature").split(",")).hasSize(1);
        assertThat(verify(after, List.of(subscription.secret()))).isFalse();
        assertThat(verify(after, List.of(rotated))).isTrue();
    }

    /**
     * Checked at subscription time is not enough: DNS (or here, the stored URL) can point somewhere
     * else later. The client's resolver re-checks on every connection, so the request never leaves.
     */
    @Test
    void aDestinationThatBecamePrivateIsNotContactedAtDeliveryTime() {
        Subscription subscription = subscribe("inventory_levels/update");
        tenant.runInStore(StoreId.of(shop.storeId()), () -> jdbc.sql(
                "UPDATE webhook_subscriptions SET url = 'http://169.254.169.254/latest/meta-data/' WHERE id = :id")
                .param("id", subscription.id()).update());

        stockChanged(1);

        JsonNode delivery = onlyDelivery(subscription);
        assertThat(delivery.get("status").asString()).isEqualTo("DEAD");
        assertThat(delivery.get("lastError").asString()).startsWith("destination not allowed");
        assertThat(delivery.get("lastStatusCode").isNull()).isTrue();
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** What a merchant's receiver does: HMAC over "timestamp.rawBody", constant-time compare with any v1. */
    static boolean verify(LoggedRequest request, List<String> secrets) throws Exception {
        String timestamp = request.getHeader("X-StoreForge-Timestamp");
        if (Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > 300) {
            return false;
        }
        List<String> signatures = Arrays.stream(request.getHeader("X-StoreForge-Signature").split(","))
                .map(String::trim).filter(s -> s.startsWith("v1=")).map(s -> s.substring(3)).toList();
        for (String secret : secrets) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + request.getBodyAsString())
                    .getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
            for (String signature : signatures) {
                if (java.security.MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Runs everything due at {@code at}, in as many rounds as needed: one round starts at most 4
     * deliveries (bulkhead), and the shared test database also holds other tests' retries.
     */
    void runDueAt(Instant at) {
        for (int round = 0; round < 50 && worker.runOnce(at) > 0; round++) {
            // keep going until nothing is due
        }
    }

    String base() {
        return "/admin/v1/stores/" + shop.storeId() + "/webhook-subscriptions";
    }

    JsonNode onlyDelivery(Subscription subscription) {
        JsonNode data = Json.parse(api.get(base() + "/" + subscription.id() + "/deliveries", shop.auth()).body()).get("data");
        assertThat(data).hasSize(1);
        return data.get(0);
    }

    JsonNode attempts(Subscription subscription) {
        String id = onlyDelivery(subscription).get("id").asString();
        return Json.parse(api.get(base() + "/" + subscription.id() + "/deliveries/" + id, shop.auth()).body()).get("attempts");
    }

    String subscriptionStatus(Subscription subscription) {
        return Json.parse(api.get(base() + "/" + subscription.id(), shop.auth()).body()).get("status").asString();
    }

    static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
