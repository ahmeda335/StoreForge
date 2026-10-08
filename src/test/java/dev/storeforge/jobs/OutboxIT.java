package dev.storeforge.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The transactional outbox: atomic with the business change, relayed once, traced end to end. */
class OutboxIT extends IntegrationTest {

    @Autowired
    Outbox outbox;

    @Autowired
    JobWorker worker;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    SdkTracerProvider tracerProvider;

    /** The dual-write problem, solved: a rolled-back business transaction leaves no event behind. */
    @Test
    void eventsOfRolledBackTransactionsNeverExist() {
        String aggregateId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outbox.append(null, "test", aggregateId, "SomethingHappened", 1, Map.of("x", 1));
            throw new IllegalStateException("business rule failed after the event was appended");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(eventsFor(aggregateId)).isZero();
    }

    @Test
    void appendingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.append(null, "test", "x", "SomethingHappened", 1, Map.of()))
                .hasMessageContaining("No existing transaction");
    }

    @Test
    void eachEventBecomesExactlyOneJobPerSubscribedHandlerEvenWhenRelayedAgain() {
        Owner shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        UUID variant = new CatalogFlows(api).createVariant(shop, "Relay test");
        new CatalogFlows(api).adjustStock(shop, variant, 1, "RECEIVED");
        String orderId = new StorefrontFlows(api, shop).placeOrder(variant, 1, uniqueEmail()).get("orderId").asString();
        worker.drain();

        UUID eventId = jdbc.sql("SELECT id FROM outbox_events WHERE aggregate_id = :id AND event_type = 'OrderPlaced'")
                .param("id", orderId).query(UUID.class).single();
        // Simulate a relay crash after inserting jobs but before marking published: publish again.
        jdbc.sql("UPDATE outbox_events SET published_at = NULL WHERE id = :id").param("id", eventId).update();
        worker.drain();

        List<String> jobTypes = jdbc.sql("SELECT type FROM jobs WHERE event_id = :id").param("id", eventId).query(String.class).list();
        assertThat(jobTypes).containsExactlyInAnyOrder("event:order-confirmation-emails", "event:webhooks");
    }

    /** request → outbox row (traceparent) → relay → job span: one trace, visible end to end in Tempo. */
    @Test
    void theConsumerSpanContinuesTheProducersTrace() {
        Owner shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        UUID variant = new CatalogFlows(api).createVariant(shop, "Traced");
        new CatalogFlows(api).adjustStock(shop, variant, 1, "RECEIVED");
        StorefrontFlows storefront = new StorefrontFlows(api, shop);
        var cart = storefront.newCart();
        UUID checkoutId = storefront.readyCheckout(cart, variant, 1, uniqueEmail());
        spans.reset();

        HttpResponse<String> completed = storefront.complete(cart, checkoutId, "10.00", UUID.randomUUID().toString());
        String traceId = completed.headers().firstValue("X-Trace-Id").orElseThrow();
        worker.drain();
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        assertThat(spans.getFinishedSpanItems())
                .filteredOn(span -> span.getName().equals("job event:order-confirmation-emails"))
                .anySatisfy(span -> assertThat(span.getTraceId()).isEqualTo(traceId));
    }

    private int eventsFor(String aggregateId) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :id").param("id", aggregateId)
                .query(Integer.class).single();
    }
}
