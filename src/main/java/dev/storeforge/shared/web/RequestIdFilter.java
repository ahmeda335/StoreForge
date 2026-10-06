package dev.storeforge.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every HTTP request a request ID before anything else runs, so that every log line
 * written while handling the request (including security failures) carries it.
 *
 * <p>The ID is put into the SLF4J MDC, which the structured (JSON) log encoder writes as a field,
 * and echoed in the response header so clients can quote it in support requests.
 *
 * <p>The MDC is thread-local and servlet threads are pooled: the {@code finally} block is what
 * prevents the next request on this thread from inheriting this request's ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestIds.acceptIncoming(request.getHeader(RequestIds.HEADER))
                .orElseGet(RequestIds::generate);

        request.setAttribute(RequestIds.REQUEST_ATTRIBUTE, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        MDC.put(RequestIds.MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
        }
    }
}
