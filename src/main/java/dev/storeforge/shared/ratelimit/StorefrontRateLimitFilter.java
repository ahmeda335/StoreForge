package dev.storeforge.shared.ratelimit;

import dev.storeforge.shared.ratelimit.DistributedRateLimiter.Decision;
import dev.storeforge.shared.ratelimit.DistributedRateLimiter.Limit;
import dev.storeforge.shared.ratelimit.StorefrontRateLimitProperties.Spec;
import dev.storeforge.shared.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Abuse limits for the anonymous storefront API (OWASP API4 and API6), per client address: a
 * general budget for every request, plus tighter budgets for the flows attackers automate (cart
 * creation, checkout completion = card testing, discount codes = guessing, search = expensive).
 *
 * <p>The client address is {@code request.getRemoteAddr()}, which honours X-Forwarded-For only
 * from configured proxies ({@code server.tomcat.remoteip.internal-proxies}). IPv6 clients are
 * grouped by /64: one host typically controls a whole /64 and could otherwise rotate addresses.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class StorefrontRateLimitFilter extends OncePerRequestFilter {

    private record Rule(String method, Pattern path, Limit limit) {
    }

    private final DistributedRateLimiter limiter;
    private final JsonMapper jsonMapper;
    private final Limit general;
    private final List<Rule> rules;

    public StorefrontRateLimitFilter(DistributedRateLimiter limiter, JsonMapper jsonMapper, StorefrontRateLimitProperties properties) {
        this.limiter = limiter;
        this.jsonMapper = jsonMapper;
        this.general = limit("storefront", properties.general());
        this.rules = List.of(
                new Rule("POST", Pattern.compile("/storefront/v1/carts/?"), limit("cart-creation", properties.cartCreation())),
                new Rule("POST", Pattern.compile("/storefront/v1/checkouts/[^/]+/completion/?"),
                        limit("checkout-completion", properties.checkoutCompletion())),
                new Rule("PUT", Pattern.compile("/storefront/v1/checkouts/[^/]+/discount/?"), limit("discount-attempts", properties.discountAttempts())),
                new Rule("GET", Pattern.compile("/storefront/v1/search/?"), limit("search", properties.search())));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/storefront/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientKey(request.getRemoteAddr());
        List<Limit> applicable = new ArrayList<>(List.of(general));
        for (Rule rule : rules) {
            if (rule.method().equals(request.getMethod()) && rule.path().matcher(request.getRequestURI()).matches()) {
                applicable.add(rule.limit());
            }
        }
        for (Limit limit : applicable) {
            Decision decision = limiter.tryConsume(limit, client);
            if (!decision.allowed()) {
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                ProblemResponses.write(jsonMapper, request, response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                        "Too many requests. Try again in " + decision.retryAfterSeconds() + " seconds.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** IPv4 as is; IPv6 reduced to its /64 network. */
    static String clientKey(String address) {
        try {
            InetAddress parsed = InetAddress.getByName(address);   // a literal: no DNS lookup
            if (parsed instanceof Inet6Address) {
                byte[] bytes = parsed.getAddress();
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < 8; i += 2) {
                    prefix.append(String.format("%02x%02x:", bytes[i], bytes[i + 1]));
                }
                return prefix.append(":/64").toString();
            }
            return parsed.getHostAddress();
        } catch (UnknownHostException e) {
            return address;
        }
    }

    private static Limit limit(String name, Spec spec) {
        return new Limit(name, spec.capacity(), spec.period());
    }
}
