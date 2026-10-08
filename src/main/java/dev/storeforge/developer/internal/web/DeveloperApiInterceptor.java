package dev.storeforge.developer.internal.web;

import dev.storeforge.developer.ApiClientPrincipal;
import dev.storeforge.developer.DeveloperApi;
import dev.storeforge.developer.RequiresScope;
import dev.storeforge.developer.Scope;
import dev.storeforge.developer.internal.DeveloperExceptions;
import dev.storeforge.developer.internal.ratelimit.ApiRateLimiter;
import dev.storeforge.shared.ratelimit.RateLimitExceededException;
import dev.storeforge.stores.PermissionDeniedException;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreInfo;
import dev.storeforge.stores.StoreNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The developer API's cross-cutting rules, in this order, before any controller code runs:
 * <ol>
 *   <li>API version: supported (else 404), echoed in {@code X-StoreForge-Api-Version};</li>
 *   <li>store binding: the path's store must be the credential's store (else 404, as for any store
 *       you can't see), and active;</li>
 *   <li>rate limit: per credential and per store (429 + Retry-After; budget in X-RateLimit-* headers);</li>
 *   <li>scope: {@link RequiresScope} of the endpoint ⊆ credential scopes (403 insufficient_scope).
 *       A handler WITHOUT the annotation fails closed (500), and an architecture test stops it earlier.</li>
 * </ol>
 * Usage is counted by {@link UsageFilter}, not here: Spring calls {@code afterCompletion} only when
 * {@code preHandle} returned true, so every request rejected HERE (429, 403, 404) would go
 * uncounted, including all rate-limited ones (found by RateLimitIT).
 */
@Component
@RequiredArgsConstructor
class DeveloperApiInterceptor implements HandlerInterceptor {

    static final String RATE_LIMITED = DeveloperApiInterceptor.class.getName() + ".rateLimited";

    private final ApiRateLimiter rateLimiter;
    private final StoreDirectory stores;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> path = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String version = path == null ? null : path.get("apiVersion");
        if (version == null || !DeveloperApi.SUPPORTED_VERSIONS.contains(version)) {
            throw new DeveloperExceptions.UnsupportedApiVersion();
        }
        response.setHeader(DeveloperApi.VERSION_HEADER, version);

        ApiClientPrincipal client = principal();
        if (client == null || !client.storeId().toString().equals(path.get("storeId"))) {
            throw new StoreNotFoundException();
        }
        if (!stores.find(client.storeId()).map(StoreInfo::active).orElse(false)) {
            throw new PermissionDeniedException("The store is suspended.");
        }

        ApiRateLimiter.Decision decision = rateLimiter.tryConsume(client.storeId().value(), client.credentialId());
        response.setHeader("X-RateLimit-Limit", Long.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        if (!decision.allowed()) {
            request.setAttribute(RATE_LIMITED, Boolean.TRUE);
            throw new RateLimitExceededException(Duration.ofSeconds(decision.retryAfterSeconds()));
        }

        RequiresScope required = method.getMethodAnnotation(RequiresScope.class);
        if (required == null) {
            throw new IllegalStateException("Developer endpoint without @RequiresScope: " + method);
        }
        List<Scope> missing = Arrays.stream(required.value()).filter(scope -> !client.has(scope)).toList();
        if (!missing.isEmpty()) {
            throw new DeveloperExceptions.InsufficientScope(missing);
        }
        return true;
    }

    static @Nullable ApiClientPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof ApiClientPrincipal client ? client : null;
    }
}
