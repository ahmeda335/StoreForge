package dev.storeforge.payments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.payments.internal.provider.FakePaymentProvider;
import dev.storeforge.payments.internal.provider.FakePaymentProvider.Failure;
import dev.storeforge.payments.internal.provider.FakePaymentProvider.Webhook;
import dev.storeforge.payments.internal.provider.StripeFormat;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.PaymentFlows;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows.Placed;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Card payments end to end: storefront → provider (fake Stripe) → signed webhook → job → order PAID. */
class CardPaymentIT extends IntegrationTest {

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    JobWorker worker;

    @Autowired
    JdbcClient jdbc;

    Owner shop;
    UUID variant;
    PaymentFlows payments;

    @BeforeEach
    void shopWithStock() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        variant = new CatalogFlows(api).createVariant(shop, "Desk lamp");
        new CatalogFlows(api).adjustStock(shop, variant, 10, "RECEIVED");
        payments = new PaymentFlows(api, shop, provider);
    }

    @Test
    void aSuccessfulCardPaymentPaysTheOrderAndSellsTheStock() {
        Placed placed = payments.storefront().place(variant, 2, uniqueEmail());

        JsonNode session = payments.started(placed);
        assertThat(session.get("status").asString()).isEqualTo("PENDING");
        assertThat(session.get("amount").get("amount").asString()).as("from the order, server side").isEqualTo("20.00");
        assertThat(session.get("clientSecret").asString()).contains("_secret_");

        payments.deliverAll(payments.pay(session, FakePaymentProvider.SUCCESS_CARD));
        worker.drain();

        JsonNode status = payments.paymentStatus(placed);
        assertThat(status.get("status").asString()).isEqualTo("CAPTURED");
        assertThat(status.get("clientSecret").isNull()).as("not handed out once paid").isTrue();
        JsonNode order = payments.order(placed.orderId());
        assertThat(order.get("financialStatus").asString()).isEqualTo("PAID");
        assertThat(payments.timeline(placed.orderId())).containsExactly("PLACED", "PAID");
        JsonNode level = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/variants/" + variant + "/inventory", shop.auth()).body());
        assertThat(level.get("onHand").asInt()).as("sold").isEqualTo(8);
        assertThat(level.get("reserved").asInt()).isZero();
        JsonNode merchantView = payments.merchantPayment(placed.orderId());
        assertThat(merchantView.get("captured").get("amount").asString()).isEqualTo("20.00");
        assertThat(merchantView.get("operations").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void startingAPaymentTwiceOrConcurrentlyGivesOnePaymentAndOneProviderIntent() throws Exception {
        Placed placed = payments.storefront().place(variant, 1, uniqueEmail());
        int intentsBefore = provider.intentIds().size();

        List<HttpResponse<String>> responses;
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<HttpResponse<String>>> calls = IntStream.range(0, 8)
                    .<Callable<HttpResponse<String>>>mapToObj(i -> () -> payments.startPayment(placed)).toList();
            List<Future<HttpResponse<String>>> futures = executor.invokeAll(calls);
            responses = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        HttpResponse<String> again = payments.startPayment(placed);

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(responses.stream().map(r -> Json.parse(r.body()).get("paymentId").asString()).distinct()).hasSize(1);
        assertThat(responses.stream().map(r -> Json.parse(r.body()).get("clientSecret").asString()).distinct()).hasSize(1);
        assertThat(Json.parse(again.body()).get("paymentId").asString()).isEqualTo(Json.parse(responses.getFirst().body()).get("paymentId").asString());
        assertThat(provider.intentIds()).as("same idempotency key → one intent at the provider").hasSize(intentsBefore + 1);
    }

    /**
     * The "unknown outcome": the provider created the intent but the response was lost (read
     * timeout). We answer 503 "retry", NOT "failed". The retry repeats the SAME key and gets the
     * SAME intent: the customer can't end up with two payments for one order.
     */
    @Test
    void aLostProviderResponseIsRetriedSafelyWithTheSameKey() {
        Placed placed = payments.storefront().place(variant, 1, uniqueEmail());
        String key = "payment:" + placed.orderId();
        int intentsBefore = provider.intentIds().size();
        provider.failNext(Failure.RESPONSE_LOST);

        HttpResponse<String> first = payments.startPayment(placed);

        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(first.headers().firstValue("Retry-After")).hasValue("5");
        assertThat(Json.parse(first.body()).get("code").asString()).isEqualTo("payment_provider_unavailable");
        assertThat(provider.intentIds()).as("it DID happen at the provider").hasSize(intentsBefore + 1);
        JsonNode operation = payments.merchantPayment(placed.orderId()).get("operations").get(0);
        assertThat(operation.get("status").asString()).isEqualTo("UNKNOWN");

        JsonNode retried = payments.started(placed);

        assertThat(retried.get("status").asString()).isEqualTo("PENDING");
        assertThat(provider.createCalls(key)).isEqualTo(2);
        assertThat(provider.intentIds()).as("still one intent").hasSize(intentsBefore + 1);
        operation = payments.merchantPayment(placed.orderId()).get("operations").get(0);
        assertThat(operation.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(operation.get("attempts").asInt()).isEqualTo(2);
    }

    @Test
    void aDeclinedCardIsRecordedAndTheShopperCanPayWithAnotherCard() {
        Placed placed = payments.storefront().place(variant, 1, uniqueEmail());
        JsonNode session = payments.started(placed);

        payments.deliverAll(payments.pay(session, FakePaymentProvider.DECLINED_CARD));
        worker.drain();

        JsonNode status = payments.paymentStatus(placed);
        assertThat(status.get("status").asString()).isEqualTo("PENDING");
        assertThat(status.get("failureCode").asString()).isEqualTo("card_declined");
        assertThat(status.get("clientSecret").asString()).as("the form can be submitted again").isNotBlank();
        assertThat(payments.order(placed.orderId()).get("financialStatus").asString()).isEqualTo("PENDING");

        payments.deliverAll(payments.pay(session, FakePaymentProvider.SUCCESS_CARD));
        worker.drain();

        assertThat(payments.order(placed.orderId()).get("financialStatus").asString()).isEqualTo("PAID");
        assertThat(payments.timeline(placed.orderId())).containsExactly("PLACED", "PAYMENT_FAILED", "PAID");
    }

    /** Providers retry deliveries, sometimes concurrently: the money is applied once, the order paid once. */
    @Test
    void duplicateAndConcurrentWebhookDeliveriesAreAppliedOnce() throws Exception {
        Placed placed = payments.storefront().place(variant, 1, uniqueEmail());
        Webhook succeeded = payments.pay(payments.started(placed), FakePaymentProvider.SUCCESS_CARD).getFirst();

        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = executor.invokeAll(IntStream.range(0, 6)
                    .<Callable<HttpResponse<String>>>mapToObj(i -> () -> payments.deliver(succeeded)).toList());
            for (var future : futures) {
                assertThat(future.get().statusCode()).as(future.get().body()).isEqualTo(200);
            }
        }
        worker.drain();
        payments.deliverAll(List.of(succeeded));   // a late redelivery
        // The same fact under ANOTHER event id (resent event, or reconciliation racing the webhook): passes
        // the event-id dedup, so only the payment's own state check stops a second capture, which would
        // otherwise look like a payment for an already-paid order and be refunded automatically.
        String eventId = Json.parse(succeeded.body()).get("id").asString();
        payments.deliverAll(List.of(provider.sign(new Webhook(succeeded.type(), succeeded.body().replace(eventId, eventId + "_resent"), ""),
                Instant.now().getEpochSecond())));
        worker.drain();

        assertThat(payments.timeline(placed.orderId())).containsExactly("PLACED", "PAID");
        assertThat(payments.merchantPayment(placed.orderId()).get("refunds")).as("no refund of a legitimate payment").isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :id AND event_type = 'OrderPaid'")
                .param("id", placed.orderId().toString()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE type = 'payments.provider-event' AND payload->>'id' = :id")
                .param("id", Json.parse(succeeded.body()).get("id").asString()).query(Integer.class).single())
                .as("one processing job per event").isEqualTo(1);
    }

    /** /webhooks has no user authentication: the signature is the authentication. */
    @Test
    void forgedReplayedOrUnsignedWebhooksAreRejected() {
        Placed placed = payments.storefront().place(variant, 1, uniqueEmail());
        Webhook genuine = payments.pay(payments.started(placed), FakePaymentProvider.SUCCESS_CARD).getFirst();

        Webhook tampered = new Webhook(genuine.type(), genuine.body().replace("\"amount\":1000", "\"amount\":1"), genuine.signature());
        Webhook replayed = provider.sign(genuine, Instant.now().minusSeconds(600).getEpochSecond());
        Webhook forged = new Webhook(genuine.type(), genuine.body(),
                StripeFormat.sign("whsec_guessed", Instant.now().getEpochSecond(), genuine.body()));

        for (Webhook bad : List.of(tampered, replayed, forged)) {
            HttpResponse<String> response = payments.deliver(bad);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(Json.parse(response.body()).get("code").asString()).isEqualTo("invalid_webhook");
        }
        assertThat(api.post("/webhooks/v1/payments/fake", genuine.body()).statusCode()).as("no signature").isEqualTo(400);
        assertThat(api.post("/webhooks/v1/payments/stripe", genuine.body(), Map.of(StripeFormat.SIGNATURE_HEADER, genuine.signature()))
                .statusCode()).as("provider not configured").isEqualTo(404);
        worker.drain();
        assertThat(payments.order(placed.orderId()).get("financialStatus").asString()).isEqualTo("PENDING");

        payments.deliverAll(List.of(genuine));
        worker.drain();
        assertThat(payments.order(placed.orderId()).get("financialStatus").asString()).isEqualTo("PAID");
    }

    @Test
    void onlyCompletedCheckoutsOfThisShopperCanBePaid() {
        var storefront = payments.storefront();
        var cart = storefront.newCart();
        UUID openCheckout = storefront.readyCheckout(cart, variant, 1, uniqueEmail());
        HttpResponse<String> notCompleted = api.post("/storefront/v1/checkouts/" + openCheckout + "/payment", "", storefront.headers(cart.token()));
        assertThat(notCompleted.statusCode()).isEqualTo(409);
        assertThat(Json.parse(notCompleted.body()).get("code").asString()).isEqualTo("checkout_not_completed");

        Placed placed = storefront.place(variant, 1, uniqueEmail());
        HttpResponse<String> wrongToken = api.post("/storefront/v1/checkouts/" + placed.checkoutId() + "/payment", "",
                storefront.headers(storefront.newCart().token()));
        assertThat(wrongToken.statusCode()).isEqualTo(404);
    }
}
