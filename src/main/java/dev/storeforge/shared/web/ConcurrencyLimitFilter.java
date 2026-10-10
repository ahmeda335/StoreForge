package dev.storeforge.shared.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Load shedding: at most {@code max-concurrent-requests} requests are processed at once per
 * instance; the next ones wait briefly for a slot, then get 503 + Retry-After at once.
 *
 * <p>With virtual threads every accepted request gets a thread immediately: nothing bounded the
 * work in progress any more (a platform thread pool used to). Under overload, thousands of requests
 * queued for 10 database connections, each holding its buffers, until the heap filled and the JVM
 * spent all its time in GC (EXP-023: 1,432 full GCs, health checks timing out). A bounded, fast
 * "no" keeps the instance healthy and its accepted requests fast; clients and load balancers retry.
 * The management port (health, metrics) is a separate server and is never shed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

    private final Semaphore slots;
    private final long waitMillis;
    private final JsonMapper jsonMapper;
    private final Counter shed;

    public ConcurrencyLimitFilter(HttpEdgeProperties properties, JsonMapper jsonMapper, MeterRegistry meterRegistry) {
        this.slots = new Semaphore(properties.maxConcurrentRequests());
        this.waitMillis = properties.concurrencyWait().toMillis();
        this.jsonMapper = jsonMapper;
        int limit = properties.maxConcurrentRequests();
        Gauge.builder("storeforge.http.requests.in_flight", slots, s -> limit - s.availablePermits()).register(meterRegistry);
        this.shed = Counter.builder("storeforge.http.requests.shed").register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            shed.increment();
            response.setHeader("Retry-After", "1");
            ProblemResponses.write(jsonMapper, request, response, HttpStatus.SERVICE_UNAVAILABLE, "overloaded",
                    "The service is at capacity. Retry in a moment.");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }
}
