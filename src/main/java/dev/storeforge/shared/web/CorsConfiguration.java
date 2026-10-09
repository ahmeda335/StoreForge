package dev.storeforge.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which browser origins may call which API (blueprint 18, API8). Used by the security chains
 * ({@code http.cors(...)}), so preflight requests are answered before authentication.
 * <ul>
 *   <li>/auth, /admin: only the configured admin frontends, with credentials (the refresh cookie).</li>
 *   <li>/storefront: any origin, never credentials. Public data plus header tokens: an origin can't
 *       borrow a shopper's identity because the browser holds none for it.</li>
 *   <li>/api (developer), /webhooks, /oauth: none. Servers don't need CORS; pages are same-origin.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class CorsConfiguration {

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(HttpEdgeProperties properties) {
        var admin = new org.springframework.web.cors.CorsConfiguration();
        admin.setAllowedOrigins(properties.adminOrigins());
        admin.setAllowCredentials(true);
        admin.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        admin.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-Requested-With", "X-Request-Id"));
        admin.setExposedHeaders(List.of("ETag", "Location", "Retry-After", "X-Request-Id", "Idempotent-Replayed"));
        admin.setMaxAge(Duration.ofHours(1));

        var storefront = new org.springframework.web.cors.CorsConfiguration();
        storefront.setAllowedOriginPatterns(List.of("*"));
        storefront.setAllowCredentials(false);
        storefront.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        storefront.setAllowedHeaders(List.of("Content-Type", "X-Cart-Token", "Idempotency-Key", "X-Request-Id"));
        storefront.setExposedHeaders(List.of("Retry-After", "X-Request-Id", "Idempotent-Replayed"));
        storefront.setMaxAge(Duration.ofHours(1));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/auth/**", admin);
        source.registerCorsConfiguration("/admin/**", admin);
        source.registerCorsConfiguration("/storefront/**", storefront);
        return source;
    }

    /**
     * The filter the security chains use ({@code http.cors()} picks the bean named "corsFilter").
     * Rejections are Problem Details like every other error, not Spring's plain "Invalid CORS request".
     */
    @Bean
    CorsFilter corsFilter(UrlBasedCorsConfigurationSource corsConfigurationSource, JsonMapper jsonMapper) {
        CorsFilter filter = new CorsFilter(corsConfigurationSource);
        filter.setCorsProcessor(new ProblemCorsProcessor(jsonMapper));
        return filter;
    }

    /** Only inside the security chains: Spring Boot would otherwise also register the bean for every request. */
    @Bean
    FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsFilter corsFilter) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(corsFilter);
        registration.setEnabled(false);
        return registration;
    }

    /** {@code rejectRequest} only receives the response; the request is kept for the duration of the check. */
    private static final class ProblemCorsProcessor extends DefaultCorsProcessor {

        private static final ThreadLocal<HttpServletRequest> CURRENT = new ThreadLocal<>();
        private final JsonMapper jsonMapper;

        ProblemCorsProcessor(JsonMapper jsonMapper) {
            this.jsonMapper = jsonMapper;
        }

        @Override
        public boolean processRequest(org.springframework.web.cors.@Nullable CorsConfiguration config, HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
            CURRENT.set(request);
            try {
                return super.processRequest(config, request, response);
            } finally {
                CURRENT.remove();
            }
        }

        @Override
        protected void rejectRequest(ServerHttpResponse response) throws IOException {
            HttpServletRequest request = CURRENT.get();
            if (request != null && response instanceof ServletServerHttpResponse servlet) {
                ProblemResponses.write(jsonMapper, request, servlet.getServletResponse(), HttpStatus.FORBIDDEN, "cross_site_request_rejected",
                        "This origin is not allowed to call this API from a browser.");
            } else {
                super.rejectRequest(response);
            }
        }
    }
}
