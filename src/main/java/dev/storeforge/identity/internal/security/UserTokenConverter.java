package dev.storeforge.identity.internal.security;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.identity.internal.domain.User;
import dev.storeforge.identity.internal.persistence.UserRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Second half of access-token validation. The JWT decoder already checked the signature, algorithm,
 * typ, issuer, audience, client_id and expiry: the token is authentic. Here we check that the
 * ACCOUNT is still allowed to use it:
 *
 * <ul>
 *   <li>the user still exists and is ACTIVE (disabled users are cut off immediately, not after 15 min);</li>
 *   <li>the token's security stamp matches (password reset / "log out everywhere" revokes old tokens).</li>
 * </ul>
 *
 * This costs one primary-key lookup per request. That is the price of immediate revocation with
 * stateless JWTs; P3 adds a short-lived local cache if measurements show it matters.
 */
@Component
class UserTokenConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;

    UserTokenConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("Invalid subject");
        }
        User user = users.findById(userId)
                .filter(User::canAuthenticate)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        Object stamp = jwt.getClaim(AccessTokenIssuer.SECURITY_STAMP_CLAIM);
        if (!(stamp instanceof Number number) || number.intValue() != user.securityStamp()) {
            throw new InvalidBearerTokenException("Token has been revoked");
        }
        return new UserAuthentication(new UserPrincipal(userId), jwt);
    }
}
