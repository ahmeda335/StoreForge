package dev.storeforge.developer.internal.web;

import dev.storeforge.developer.ApiClientPrincipal;
import dev.storeforge.developer.internal.ratelimit.UsageCounters;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts every authenticated developer API request with its FINAL status, whatever rejected it,
 * because it wraps the whole handling (interceptors, controller, exception resolution).
 * Placed in the developer security chain after authentication.
 */
public class UsageFilter extends OncePerRequestFilter {

    private final UsageCounters usage;
    private final Clock clock;

    public UsageFilter(UsageCounters usage, Clock clock) {
        this.usage = usage;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            ApiClientPrincipal client = DeveloperApiInterceptor.principal();
            if (client != null) {
                usage.record(client.storeId().value(), client.credentialId(), response.getStatus(),
                        Boolean.TRUE.equals(request.getAttribute(DeveloperApiInterceptor.RATE_LIMITED)), clock.instant());
            }
        }
    }
}
