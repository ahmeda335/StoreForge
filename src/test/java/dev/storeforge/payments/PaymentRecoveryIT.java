package dev.storeforge.payments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.orders.internal.application.UnpaidOrderExpiry;
import dev.storeforge.payments.internal.application.PaymentReconciliation;
import dev.storeforge.payments.internal.provider.FakePaymentProvider;
import dev.storeforge.payments.internal.provider.FakePaymentProvider.Failure;
import dev.storeforge.payments.internal.provider.PaymentGateway;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.PaymentFlows;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows.Placed;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * When things go wrong between us and the provider: lost webhooks, races between paying and
 * cancelling, paying twice, and a provider that is down. Money must always end up where it belongs.
 */
class PaymentRecoveryIT extends IntegrationTest {

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    JobWorker worker;

    @Autowired
    PaymentReconciliation reconciliation;

    @Autowired
    UnpaidOrderExpiry expiry;

    @Autowired
    TenantTransactions tenant;

    @Autowired
    JdbcClient jdbc;

    Owner shop;
    UUID variant;
    PaymentFlows payments;

    @BeforeEach
    void shopWithStock() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        variant = new CatalogFlows(api).createVariant(shop, "Chair");
        new CatalogFlows(api).adjustStock(shop, variant, 50, "RECEIVED");
        payments = new PaymentFlows(api, shop, provider);
    }

    @AfterEach
    void closeTheCircuit() {
        gateway.circuitBreaker().reset();
    }

    /** The webhook never arrives (provider incident, our outage): reconciliation asks the provider and applies it. */
    @Test
    void aLostWebhookIsRecoveredByReconciliation() {
        Placed placed = payments.storefront().place(variant, 1, uniqueEmail());
        payments.pay(payments.started(placed), FakePaymentProvider.SUCCESS_CARD);   // webhook dropped on the floor

        reconciliation.reconcile(Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(payments.order(placed.orderId()).get("financialStatus").asString()).as("not stale yet").isEqualTo("PENDING");

        reconciliation.reconcile(Instant.now().plus(Duration.ofMinutes(11)));
        worker.drain();

        assertThat(payments.order(placed.orderId()).get("financialStatus").asString()).isEqualTo("PAID");
        assertThat(payments.paymentStatus(placed).get("status").asString()).isEqualTo("CAPTURED");
    }

    /** Regression (EXP-011): one payment the provider doesn't know used to abort the whole reconciliation run. */
    @Test
    void onePoisonedPaymentDoesNotStopReconciliationOfTheOthers() {
        Placed unknownAtProvider = payments.storefront().place(variant, 1, uniqueEmail());
        payments.started(unknownAtProvider);
        tenant.runInStore(StoreId.of(shop.storeId()), () -> jdbc.sql("UPDATE payments SET provider_payment_id = 'pi_from_another_environment' WHERE order_id = :id")
                .param("id", unknownAtProvider.orderId()).update());
        Placed lostWebhook = payments.storefront().place(variant, 1, uniqueEmail());
        payments.pay(payments.started(lostWebhook), FakePaymentProvider.SUCCESS_CARD);

        var result = reconciliation.reconcile(Instant.now().plus(Duration.ofMinutes(11)));

        assertThat(result.failures()).isPositive();
        worker.drain();
        assertThat(payments.order(lostWebhook.orderId()).get("financialStatus").asString()).isEqualTo("PAID");
    }

    /**
     * The race: the shopper pays at minute 14:59, the order expires at 15:00 before the webhook is
     * processed. The cancellation finds the provider payment already succeeded; the capture is
     * applied, the order can't take it (cancelled, stock released), so the money goes back by itself.
     */
    @Test
    void moneyCapturedForAnExpiredOrderIsRefundedAutomatically() {
        Placed placed = payments.storefront().place(variant, 2, uniqueEmail());
        JsonNode session = payments.started(placed);
        var lateWebhooks = payments.pay(session, FakePaymentProvider.SUCCESS_CARD);   // not delivered yet

        expiry.expireOverdue(Instant.now().plus(Duration.ofMinutes(16)));
        worker.drain();   // OrderCancelled → cancel at provider → "already succeeded" → capture → compensation refund
        payments.deliverAll(lateWebhooks);   // the late webhook changes nothing
        worker.drain();

        JsonNode order = payments.order(placed.orderId());
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("financialStatus").asString()).isEqualTo("VOIDED");
        // Any order: the expiry ran at a simulated future time, so its timeline entry sorts after the real-time refund.
        assertThat(payments.timeline(placed.orderId())).containsExactlyInAnyOrder("PLACED", "EXPIRED", "REFUNDED");
        JsonNode payment = payments.merchantPayment(placed.orderId());
        assertThat(payment.get("status").asString()).isEqualTo("REFUNDED");
        assertThat(payment.get("refunds").get(0).get("reason").asString()).isEqualTo("order_not_payable");
        assertThat(provider.refundedAtProvider(PaymentFlows.intentId(session))).as("customer got the money back").isEqualTo(2000);
    }

    @Test
    void cancellingAnUnpaidOrderCancelsItsPaymentAtTheProvider() {
        Placed placed = payments.storefront().place(variant, 1, uniqueEmail());
        JsonNode session = payments.started(placed);

        HttpResponse<String> cancelled = api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + placed.orderId() + "/cancellation",
                "{\"reason\":\"customer_request\"}", shop.auth());
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        worker.drain();

        assertThat(provider.status(PaymentFlows.intentId(session))).isEqualTo("canceled");
        assertThat(payments.paymentStatus(placed).get("status").asString()).isEqualTo("CANCELLED");
        HttpResponse<String> restart = payments.startPayment(placed);
        assertThat(restart.statusCode()).isEqualTo(409);
        assertThat(Json.parse(restart.body()).get("code").asString()).isEqualTo("payment_closed");
    }

    /** Merchant marked it paid (cash) while the card payment also went through: the card money is returned. */
    @Test
    void aSecondPaymentForAnAlreadyPaidOrderIsRefunded() {
        Placed placed = payments.storefront().place(variant, 1, uniqueEmail());
        JsonNode session = payments.started(placed);
        api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + placed.orderId() + "/manual-payment", "{}", shop.auth());

        payments.deliverAll(payments.pay(session, FakePaymentProvider.SUCCESS_CARD));
        worker.drain();

        JsonNode order = payments.order(placed.orderId());
        assertThat(order.get("financialStatus").asString()).as("still paid (manually)").isEqualTo("PAID");
        assertThat(order.get("refunded").get("amount").asString()).as("order balance untouched").isEqualTo("0.00");
        assertThat(payments.merchantPayment(placed.orderId()).get("status").asString()).isEqualTo("REFUNDED");
        assertThat(provider.refundedAtProvider(PaymentFlows.intentId(session))).isEqualTo(1000);
    }

    /**
     * A provider that keeps failing trips the breaker: further calls fail immediately without
     * reaching the provider (no threads parked in timeouts, no extra load on it). After the pause,
     * trial calls succeed and the circuit closes again.
     */
    @Test
    void theCircuitBreakerOpensOnRepeatedProviderFailuresAndRecovers() {
        CircuitBreaker breaker = gateway.circuitBreaker();
        breaker.reset();
        List<Placed> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            orders.add(payments.storefront().place(variant, 1, uniqueEmail()));
        }
        provider.failNext(Failure.UNAVAILABLE, Failure.UNAVAILABLE, Failure.UNAVAILABLE, Failure.UNAVAILABLE, Failure.UNAVAILABLE,
                Failure.UNAVAILABLE, Failure.UNAVAILABLE, Failure.UNAVAILABLE, Failure.UNAVAILABLE, Failure.UNAVAILABLE);

        for (Placed placed : orders.subList(0, 10)) {
            assertThat(payments.startPayment(placed).statusCode()).isEqualTo(503);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = provider.calls();
        HttpResponse<String> fastFail = payments.startPayment(orders.get(10));
        assertThat(fastFail.statusCode()).isEqualTo(503);
        assertThat(provider.calls()).as("the provider was not called at all").isEqualTo(callsBefore);
        assertThat(management.get("/actuator/prometheus").body())
                .contains("resilience4j_circuitbreaker_state{application=\"storeforge\",name=\"payment-provider\",state=\"open\"} 1.0");

        breaker.transitionToHalfOpenState();   // what the 30s pause would do
        assertThat(payments.startPayment(orders.get(10)).statusCode()).isEqualTo(200);
        assertThat(payments.startPayment(orders.get(11)).statusCode()).isEqualTo(200);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(payments.startPayment(orders.getFirst()).statusCode()).as("earlier failures can simply be retried").isEqualTo(200);
    }
}
