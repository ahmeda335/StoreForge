package dev.storeforge.identity.internal.security;

import dev.storeforge.shared.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/** Security rejections in the same Problem Details format as every other error. */
final class ProblemSecurityHandlers {

    private ProblemSecurityHandlers() {
    }

    static AuthenticationEntryPoint authenticationEntryPoint(JsonMapper jsonMapper) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) -> {
            bearer.commence(request, response, exception);   // 401 + RFC 6750 WWW-Authenticate header
            ProblemResponses.write(jsonMapper, request, response, HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "A valid access token is required.");
        };
    }

    static AccessDeniedHandler accessDeniedHandler(JsonMapper jsonMapper) {
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) -> {
            try {
                ProblemResponses.write(jsonMapper, request, response, HttpStatus.FORBIDDEN, "forbidden",
                        "You are not allowed to perform this action.");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
