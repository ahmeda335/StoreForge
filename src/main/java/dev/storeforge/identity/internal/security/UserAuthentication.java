package dev.storeforge.identity.internal.security;

import dev.storeforge.identity.UserPrincipal;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/** A request authenticated by a valid merchant access token. No authorities: permissions are per store (P3). */
public class UserAuthentication extends AbstractAuthenticationToken {

    private final UserPrincipal principal;
    private final transient Jwt token;

    UserAuthentication(UserPrincipal principal, Jwt token) {
        super(List.of());
        this.principal = principal;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public UserPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }
}
