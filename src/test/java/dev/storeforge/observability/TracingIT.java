package dev.storeforge.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.inventory.Inventory;
import dev.storeforge.inventory.ReservationLine;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that traces have the SHAPE we rely on when debugging: one trace per request, database
 * work as child spans of the request, the tenant on the span, and our business observation in between.
 */
class TracingIT extends IntegrationTest {

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    SdkTracerProvider tracerProvider;

    @Autowired
    Inventory inventory;

    @Autowired
    TenantTransactions tenant;

    Owner owner;
    UUID variantId;

    @BeforeEach
    void storeWithVariant() {
        CatalogFlows catalog = new CatalogFlows(api);
        owner = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        variantId = catalog.createVariant(owner, "Traced item");
        catalog.adjustStock(owner, variantId, 5, "RECEIVED");
    }

    @Test
    void anHttpRequestIsOneTraceWithDatabaseSpansAndTheTenantAttribute() {
        HttpResponse<String> response = new CatalogFlows(api).adjustStock(owner, variantId, 1, "RECEIVED");
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();

        List<SpanData> trace = finishedSpans(traceId);

        SpanData server = trace.stream().filter(span -> span.getParentSpanContext().isValid() == false).findFirst().orElseThrow();
        assertThat(server.getName()).startsWith("http post");
        // The tag goes on the span that is current when the tenant transaction starts (inside the
        // request: Spring Security's "secured request" span). TraceQL finds it on any span:
        //   { span.storeforge.store_id = "<id>" }
        assertThat(trace).as("some span of the request carries the store id")
                .anySatisfy(span -> assertThat(span.getAttributes().get(AttributeKey.stringKey(TenantTransactions.SPAN_TAG)))
                        .isEqualTo(owner.storeId().toString()));
        assertThat(trace).as("JDBC queries appear inside the request's trace")
                .anySatisfy(span -> assertThat(span.getName()).isEqualTo("query"));
    }

    @Test
    void businessObservationsWrapTheirDatabaseWork() {
        StoreId storeId = StoreId.of(owner.storeId());
        spans.reset();

        tenant.runInStore(storeId, () -> inventory.reserve(storeId, UUID.randomUUID(),
                List.of(new ReservationLine(variantId, 1)), Duration.ofMinutes(15)));
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        SpanData reserve = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("inventory.reserve")).findFirst().orElseThrow();
        assertThat(reserve.getAttributes().get(AttributeKey.stringKey("lines"))).isEqualTo("single");
        assertThat(spans.getFinishedSpanItems())
                .as("the reservation's SQL runs as children of inventory.reserve")
                .anySatisfy(span -> {
                    assertThat(span.getName()).isEqualTo("query");
                    assertThat(span.getParentSpanId()).isEqualTo(reserve.getSpanId());
                });
    }

    private List<SpanData> finishedSpans(String traceId) {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        return spans.getFinishedSpanItems().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }
}
