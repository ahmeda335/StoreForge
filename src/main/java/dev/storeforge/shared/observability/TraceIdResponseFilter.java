package dev.storeforge.shared.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Echoes the trace id as {@code X-Trace-Id}, so anyone holding a response (a developer, a support
 * ticket, a k6 failure) can open the exact trace in Tempo. Ordered right after Spring's
 * ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1), which starts the HTTP server span.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class TraceIdResponseFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Trace-Id";

    private final Tracer tracer;

    TraceIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(HEADER, span.context().traceId());
        }
        chain.doFilter(request, response);
    }
}
