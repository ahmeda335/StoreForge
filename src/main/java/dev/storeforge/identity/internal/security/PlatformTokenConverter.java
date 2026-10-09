package dev.storeforge.identity.internal.security;

import dev.storeforge.identity.PlatformAdminPrincipal;
import dev.storeforge.identity.internal.domain.User;
import dev.storeforge.identity.internal.persistence.UserRepository;
import dev.storeforge.identity.internal.platform.PlatformMfaService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Platform tokens are checked against current state on EVERY request: the account is active, the
 * security stamp matches (password reset revokes), and the user is STILL a platform admin with a
 * confirmed second factor (revoking the row cuts off a token at once, not after its TTL).
 */
@Component
@RequiredArgsConstructor
class PlatformTokenConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;
    private final PlatformMfaService mfa;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("Invalid subject");
        }
        User user = users.findById(userId).filter(User::canAuthenticate)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        Object stamp = jwt.getClaim(AccessTokenIssuer.SECURITY_STAMP_CLAIM);
        if (!(stamp instanceof Number number) || number.intValue() != user.securityStamp()) {
            throw new InvalidBearerTokenException("Token has been revoked");
        }
        if (!mfa.isEnrolledAdmin(userId)) {
            throw new InvalidBearerTokenException("No longer a platform admin");
        }
        List<String> amr = jwt.getClaimAsStringList(PlatformTokens.AMR_CLAIM);
        var principal = new PlatformAdminPrincipal(userId, amr == null ? List.of() : List.copyOf(amr));
        AbstractAuthenticationToken authentication = new AbstractAuthenticationToken(List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))) {
            @Override
            public Object getCredentials() {
                return jwt;
            }

            @Override
            public Object getPrincipal() {
                return principal;
            }
        };
        authentication.setAuthenticated(true);
        return authentication;
    }
}
