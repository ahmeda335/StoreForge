package dev.storeforge.developer.internal.oauth;

import dev.storeforge.identity.PasswordAuthenticator;
import dev.storeforge.shared.error.StoreForgeException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * The OAuth login form authenticates through identity's {@link PasswordAuthenticator}: same rate
 * limits, lockout and enumeration resistance as the login API. A second login door must never be
 * weaker than the first. The session principal is the user id (a String: serializable as-is into
 * the pending authorization).
 */
@Component
@RequiredArgsConstructor
class PasswordAuthenticationProvider implements AuthenticationProvider {

    private final PasswordAuthenticator authenticator;

    @Override
    public Authentication authenticate(Authentication authentication) {
        String ip = authentication.getDetails() instanceof WebAuthenticationDetails details ? details.getRemoteAddress() : "unknown";
        try {
            var user = authenticator.authenticate(authentication.getName(), String.valueOf(authentication.getCredentials()), ip);
            return UsernamePasswordAuthenticationToken.authenticated(user.id().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        } catch (StoreForgeException e) {
            throw new BadCredentialsException(e.code());
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
