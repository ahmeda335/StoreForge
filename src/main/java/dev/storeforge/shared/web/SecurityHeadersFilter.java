package dev.storeforge.shared.web;

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
 * Headers for every response, on top of Spring Security's defaults (nosniff, frame DENY,
 * no-store, HSTS on HTTPS). The APIs return JSON only, so their policy forbids everything: if a
 * browser is ever tricked into rendering a response as a page, nothing in it can run, load or be
 * framed. HTML pages (OAuth login/consent) set their own, slightly wider policy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String API_POLICY = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", API_POLICY);
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        chain.doFilter(request, response);
    }
}
