package dev.storeforge.jobs.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Carries the trace across the asynchronous hop. A thread-local "current span" can't survive a
 * database row, so the producer's W3C {@code traceparent} is stored with the event/job, and the
 * worker starts its span as a CHILD of it: request → outbox → relay → job form one trace in Tempo.
 */
@Component
@RequiredArgsConstructor
class TraceContexts {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    @Nullable String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    Span startConsumerSpan(String name, @Nullable String traceParent) {
        Span.Builder builder = traceParent == null
                ? tracer.spanBuilder().setNoParent()
                : propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get);
        return builder.name(name).kind(Span.Kind.CONSUMER).start();
    }
}
