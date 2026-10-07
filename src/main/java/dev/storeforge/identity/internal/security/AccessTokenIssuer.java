package dev.storeforge.identity.internal.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Mints merchant access tokens following RFC 9068 ("JWT profile for OAuth 2.0 access tokens"):
 * header {@code typ: at+jwt}; claims iss, sub, aud, exp, iat, jti, client_id.
 *
 * <p>The explicit {@code typ} prevents token confusion: a JWT minted for another purpose (e.g. an ID
 * token) cannot be replayed as an access token. Permissions are deliberately NOT in the token: they
 * change when roles change, and a token lives for its whole TTL (ADR-003).
 */
@Component
@RequiredArgsConstructor
public class AccessTokenIssuer {

    public static final String AUDIENCE = "merchant";
    public static final String CLIENT_ID = "storeforge-merchant-web";
    public static final String SECURITY_STAMP_CLAIM = "sstamp";

    private final JwtEncoder encoder;
    private final JwtKeys keys;
    private final AuthProperties properties;
    private final Clock clock;

    public record AccessToken(String value, long expiresInSeconds) {
        @Override
        public String toString() {
            return "AccessToken[REDACTED]";
        }
    }

    public AccessToken issue(UUID userId, int securityStamp) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("client_id", CLIENT_ID)
                .claim(SECURITY_STAMP_CLAIM, securityStamp)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256)
                .keyId(keys.activeKey().getKeyID())
                .type("at+jwt")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, properties.accessTokenTtl().toSeconds());
    }
}
