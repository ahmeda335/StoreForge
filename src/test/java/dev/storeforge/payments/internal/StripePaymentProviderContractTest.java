package dev.storeforge.payments.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.storeforge.payments.internal.provider.InvalidWebhookException;
import dev.storeforge.payments.internal.provider.PaymentProvider.CreatePayment;
import dev.storeforge.payments.internal.provider.PaymentProvider.EventType;
import dev.storeforge.payments.internal.provider.PaymentProvider.PaymentStatus;
import dev.storeforge.payments.internal.provider.PaymentProvider.RefundStatus;
import dev.storeforge.payments.internal.provider.ProviderRejectedException;
import dev.storeforge.payments.internal.provider.ProviderUnavailableException;
import dev.storeforge.payments.internal.provider.StripePaymentProvider;
import io.micrometer.observation.ObservationRegistry;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract of the Stripe adapter against a fake HTTP server: what we send (auth, idempotency key,
 * form fields) and how every kind of answer is classified. The classification decides between
 * "retry with the same key", "give up" and "outcome unknown", so it is the part worth pinning.
 */
class StripePaymentProviderContractTest {

    static final String SECRET_KEY = "sk_test_123";
    static final String WEBHOOK_SECRET = "whsec_contract";
    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    WireMockServer stripe;
    StripePaymentProvider provider;

    @BeforeEach
    void start() {
        stripe = new WireMockServer(options().dynamicPort());
        stripe.start();
        provider = provider(URI.create(stripe.baseUrl()));
    }

    @AfterEach
    void stop() {
        stripe.stop();
    }

    static StripePaymentProvider provider(URI baseUrl) {
        return new StripePaymentProvider(baseUrl, SECRET_KEY, "2025-01-01", WEBHOOK_SECRET, Duration.ofMillis(500), Duration.ofMillis(500),
                Duration.ofMinutes(5), ObservationRegistry.NOOP, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAPaymentIntentWithOurIdsAndTheIdempotencyKey() {
        UUID store = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        stripe.stubFor(post("/v1/payment_intents").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"pi_123","object":"payment_intent","amount":2450,"currency":"usd",
                         "client_secret":"pi_123_secret_abc","status":"requires_payment_method"}""")));

        var created = provider.createPayment(new CreatePayment(store, payment, order, 1001, 2450, "USD"), "payment:" + order);

        assertThat(created.id()).isEqualTo("pi_123");
        assertThat(created.clientSecret()).isEqualTo("pi_123_secret_abc");
        assertThat(created.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        stripe.verify(postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Authorization", equalTo("Bearer " + SECRET_KEY))
                .withHeader("Idempotency-Key", equalTo("payment:" + order))
                .withHeader("Stripe-Version", equalTo("2025-01-01"))
                .withFormParam("amount", equalTo("2450"))
                .withFormParam("currency", equalTo("usd"))
                .withFormParam("automatic_payment_methods[enabled]", equalTo("true"))
                .withFormParam("metadata[store_id]", equalTo(store.toString()))
                .withFormParam("metadata[payment_id]", equalTo(payment.toString()))
                .withFormParam("metadata[order_id]", equalTo(order.toString())));
    }

    @Test
    void cardErrorsAreFinalRejections() {
        stripe.stubFor(post("/v1/refunds").willReturn(aResponse().withStatus(402).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\",\"decline_code\":\"insufficient_funds\"}}")));
        stripe.stubFor(post("/v1/payment_intents/pi_1/cancel").willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"payment_intent_unexpected_state\"}}")));

        assertThatThrownBy(() -> provider.refund("pi_1", 100, UUID.randomUUID(), UUID.randomUUID(), "refund:1"))
                .isInstanceOfSatisfying(ProviderRejectedException.class, e -> assertThat(e.code()).isEqualTo("insufficient_funds"));
        assertThatThrownBy(() -> provider.cancelPayment("pi_1", "cancel:1"))
                .isInstanceOfSatisfying(ProviderRejectedException.class, e -> assertThat(e.code()).isEqualTo("payment_intent_unexpected_state"));
    }

    @Test
    void serverErrorsAndKeyConflictsHaveAnUnknownOutcome() {
        stripe.stubFor(post("/v1/payment_intents").willReturn(aResponse().withStatus(500)));
        stripe.stubFor(post("/v1/refunds").willReturn(aResponse().withStatus(409)
                .withBody("{\"error\":{\"type\":\"idempotency_error\"}}")));

        assertUnavailable(() -> provider.createPayment(request(), "payment:x"), true);
        assertUnavailable(() -> provider.refund("pi_1", 100, UUID.randomUUID(), UUID.randomUUID(), "refund:1"), true);
    }

    @Test
    void rateLimitingMeansNotPerformed() {
        stripe.stubFor(post("/v1/payment_intents").willReturn(aResponse().withStatus(429)));
        assertUnavailable(() -> provider.createPayment(request(), "payment:x"), false);
    }

    /** The dangerous one: the request reached Stripe, we never got the answer. It may have been performed. */
    @Test
    void aReadTimeoutHasAnUnknownOutcome() {
        stripe.stubFor(post("/v1/payment_intents").willReturn(aResponse().withStatus(200).withFixedDelay(2_000)
                .withBody("{\"id\":\"pi_late\",\"status\":\"requires_payment_method\"}")));
        long started = System.nanoTime();

        assertUnavailable(() -> provider.createPayment(request(), "payment:x"), true);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("bounded by the read timeout").isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void aRefusedConnectionMeansNotPerformed() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var unreachable = provider(URI.create("http://localhost:" + closedPort));
        assertUnavailable(() -> unreachable.createPayment(request(), "payment:x"), false);
    }

    @Test
    void readsPaymentsAndRefunds() {
        stripe.stubFor(get("/v1/payment_intents/pi_7").willReturn(aResponse().withStatus(200)
                .withBody("{\"id\":\"pi_7\",\"status\":\"succeeded\",\"amount\":999}")));
        stripe.stubFor(get("/v1/refunds/re_7").willReturn(aResponse().withStatus(200)
                .withBody("{\"id\":\"re_7\",\"status\":\"pending\"}")));

        assertThat(provider.retrievePayment("pi_7").status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(provider.retrieveRefund("re_7").status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void verifiesWebhooksBeforeParsingThem() throws Exception {
        UUID store = UUID.randomUUID();
        String body = """
                {"id":"evt_1","object":"event","type":"payment_intent.succeeded","data":{"object":{"id":"pi_1","object":"payment_intent",
                 "status":"succeeded","metadata":{"store_id":"%s","payment_id":"%s"}}}}""".formatted(store, UUID.randomUUID());
        String signature = StripeFormatTest.header(WEBHOOK_SECRET, NOW.getEpochSecond(), body);

        var event = provider.parseWebhook(body, signature);

        assertThat(event.type()).isEqualTo(EventType.PAYMENT_SUCCEEDED);
        assertThat(event.storeId()).isEqualTo(store);
        assertThatThrownBy(() -> provider.parseWebhook(body.replace("succeeded", "canceled"), signature))
                .isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    void refusesToStartWithoutCredentials() {
        assertThatThrownBy(() -> new StripePaymentProvider(URI.create("http://localhost"), "", null, "", Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofMinutes(5), ObservationRegistry.NOOP, JsonMapper.builder().build(), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static CreatePayment request() {
        return new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1001, 100, "USD");
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, boolean outcomeUnknown) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProviderUnavailableException.class,
                e -> assertThat(e.outcomeUnknown()).as("outcome unknown").isEqualTo(outcomeUnknown));
    }
}
