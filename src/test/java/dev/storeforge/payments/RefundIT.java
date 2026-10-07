package dev.storeforge.payments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.payments.internal.provider.FakePaymentProvider;
import dev.storeforge.payments.internal.provider.FakePaymentProvider.Failure;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.PaymentFlows;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows.Placed;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class RefundIT extends IntegrationTest {

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    JobWorker worker;

    Owner shop;
    UUID variant;
    PaymentFlows payments;

    @BeforeEach
    void shopWithStock() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        variant = new CatalogFlows(api).createVariant(shop, "Mug");
        new CatalogFlows(api).adjustStock(shop, variant, 20, "RECEIVED");
        payments = new PaymentFlows(api, shop, provider);
    }

    @AfterEach
    void providerBackToNormal() {
        provider.refundsPending(false);
    }

    record Paid(Placed placed, String intentId) {
    }

    /** An order of {@code quantity} × 10.00, paid by card. */
    Paid paidOrder(int quantity) {
        Placed placed = payments.storefront().place(variant, quantity, uniqueEmail());
        JsonNode session = payments.started(placed);
        payments.deliverAll(payments.pay(session, FakePaymentProvider.SUCCESS_CARD));
        worker.drain();
        return new Paid(placed, PaymentFlows.intentId(session));
    }

    @Test
    void partialThenFullRefundAfterWhichTheOrderCanBeCancelled() {
        Paid paid = paidOrder(3);
        UUID orderId = paid.placed().orderId();

        HttpResponse<String> partial = payments.refund(orderId, "10.00", PaymentFlows.newKey());
        assertThat(partial.statusCode()).as(partial.body()).isEqualTo(201);
        assertThat(Json.parse(partial.body()).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(payments.order(orderId).get("financialStatus").asString()).isEqualTo("PARTIALLY_REFUNDED");

        assertThat(payments.refund(orderId, "20.00", PaymentFlows.newKey()).statusCode()).isEqualTo(201);
        JsonNode order = payments.order(orderId);
        assertThat(order.get("financialStatus").asString()).isEqualTo("REFUNDED");
        assertThat(order.get("refunded").get("amount").asString()).isEqualTo("30.00");
        assertThat(provider.refundedAtProvider(paid.intentId())).isEqualTo(3000);

        HttpResponse<String> more = payments.refund(orderId, "0.01", PaymentFlows.newKey());
        assertThat(more.statusCode()).isEqualTo(409);
        assertThat(Json.parse(more.body()).get("code").asString()).isEqualTo("payment_not_refundable");
        HttpResponse<String> cancelled = api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + orderId + "/cancellation",
                "{\"reason\":\"customer_request\"}", shop.auth());
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(Json.parse(cancelled.body()).get("financialStatus").asString()).isEqualTo("REFUNDED");
        worker.drain();
        assertThat(payments.timeline(orderId)).containsExactly("PLACED", "PAID", "REFUNDED", "REFUNDED", "CANCELLED");
    }

    @Test
    void aRetriedRefundRequestRefundsOnce() {
        Paid paid = paidOrder(2);
        UUID orderId = paid.placed().orderId();
        String key = PaymentFlows.newKey();

        HttpResponse<String> first = payments.refund(orderId, "5.00", key);
        HttpResponse<String> retry = payments.refund(orderId, "5.00", key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(Json.parse(retry.body()).get("id").asString()).isEqualTo(Json.parse(first.body()).get("id").asString());
        assertThat(provider.refundIds(paid.intentId())).hasSize(1);
        assertThat(payments.refund(orderId, "6.00", key).statusCode()).as("same key, different request").isEqualTo(422);
        assertThat(Json.parse(payments.refund(orderId, "5.00", null).body()).get("code").asString()).isEqualTo("idempotency_key_required");
    }

    /** The payment row lock queues concurrent refunds; each sees the others' pending amounts. */
    @Test
    void concurrentRefundsNeverExceedTheCapturedAmount() throws Exception {
        Paid paid = paidOrder(2);   // 20.00
        UUID orderId = paid.placed().orderId();

        List<HttpResponse<String>> responses;
        try (var executor = Executors.newFixedThreadPool(10)) {
            responses = executor.invokeAll(IntStream.range(0, 10)
                    .<Callable<HttpResponse<String>>>mapToObj(i -> () -> payments.refund(orderId, "5.00", PaymentFlows.newKey())).toList())
                    .stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        }

        assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(4);
        assertThat(responses).filteredOn(r -> r.statusCode() == 422)
                .hasSize(6)
                .allSatisfy(r -> assertThat(Json.parse(r.body()).get("code").asString()).isEqualTo("refund_exceeds_available"));
        assertThat(provider.refundedAtProvider(paid.intentId())).isEqualTo(2000);
        assertThat(payments.order(orderId).get("financialStatus").asString()).isEqualTo("REFUNDED");
    }

    /**
     * The provider refunded, but the answer was lost. The merchant sees PENDING (not "failed"); the
     * queued job retries with the SAME key and gets the SAME refund: money returned once.
     */
    @Test
    void aRefundWhoseResponseWasLostIsCompletedByTheRetryJob() {
        Paid paid = paidOrder(1);
        UUID orderId = paid.placed().orderId();
        provider.failNext(Failure.RESPONSE_LOST);

        HttpResponse<String> requested = payments.refund(orderId, "10.00", PaymentFlows.newKey());

        assertThat(requested.statusCode()).isEqualTo(201);
        assertThat(Json.parse(requested.body()).get("status").asString()).isEqualTo("PENDING");
        assertThat(provider.refundedAtProvider(paid.intentId())).as("it did happen at the provider").isEqualTo(1000);
        assertThat(payments.order(orderId).get("financialStatus").asString()).isEqualTo("PAID");

        worker.runOnce(Instant.now().plus(Duration.ofMinutes(1)));

        assertThat(payments.order(orderId).get("financialStatus").asString()).isEqualTo("REFUNDED");
        assertThat(provider.refundIds(paid.intentId())).as("one refund at the provider").hasSize(1);
        assertThat(payments.merchantPayment(orderId).get("refunds").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aRefundThatThePaymentProviderSettlesLaterCompletesByWebhook() {
        Paid paid = paidOrder(1);
        UUID orderId = paid.placed().orderId();
        provider.refundsPending(true);

        HttpResponse<String> requested = payments.refund(orderId, "4.00", PaymentFlows.newKey());
        assertThat(Json.parse(requested.body()).get("status").asString()).isEqualTo("PENDING");
        String providerRefund = Json.parse(requested.body()).get("providerRefundId").asString();

        payments.deliverAll(provider.settleRefund(providerRefund, true));
        worker.drain();

        assertThat(payments.order(orderId).get("financialStatus").asString()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(payments.merchantPayment(orderId).get("refunds").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void refundsNeedThePermissionAndACapturedPayment() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        UUID readOnly = stores.createRole(shop, "Support", "orders:read");
        var support = stores.addMember(shop, uniqueEmail(), readOnly);
        Paid paid = paidOrder(1);

        HttpResponse<String> forbidden = payments.refund(StoreFlows.bearer(support.accessToken()), paid.placed().orderId(), "1.00",
                PaymentFlows.newKey());
        assertThat(forbidden.statusCode()).isEqualTo(403);

        Placed unpaid = payments.storefront().place(variant, 1, uniqueEmail());
        assertThat(Json.parse(payments.refund(unpaid.orderId(), "1.00", PaymentFlows.newKey()).body()).get("code").asString())
                .as("no payment started").isEqualTo("payment_not_found");
        payments.started(unpaid);
        assertThat(Json.parse(payments.refund(unpaid.orderId(), "1.00", PaymentFlows.newKey()).body()).get("code").asString())
                .as("not captured").isEqualTo("payment_not_refundable");
        assertThat(Json.parse(payments.refund(paid.placed().orderId(), "10.01", PaymentFlows.newKey()).body()).get("code").asString())
                .isEqualTo("refund_exceeds_available");
        assertThat(payments.refund(paid.placed().orderId(), "0", PaymentFlows.newKey()).statusCode()).isEqualTo(400);
    }
}
