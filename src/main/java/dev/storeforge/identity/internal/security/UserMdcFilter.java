package dev.storeforge.identity.internal.security;

import dev.storeforge.identity.PlatformAdminPrincipal;
import dev.storeforge.identity.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** After authentication: adds {@code userId} to every log line of the request (an ID, never the email). */
class UserMdcFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            MDC.put(MDC_KEY, principal.userId().toString());
        } else if (authentication != null && authentication.getPrincipal() instanceof PlatformAdminPrincipal admin) {
            MDC.put(MDC_KEY, admin.userId().toString());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
