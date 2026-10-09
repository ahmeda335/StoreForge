package dev.storeforge.identity.internal.security;

import dev.storeforge.identity.internal.security.AccessTokenIssuer.AccessToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * PLATFORM access tokens: same signing keys as merchant tokens, but their own audience and
 * client_id, so neither kind is accepted where the other is expected (each decoder pins its
 * audience). Short-lived, no refresh token: after expiry the admin presents a second factor again.
 */
@Component
@RequiredArgsConstructor
public class PlatformTokens {

    public static final String AUDIENCE = "platform";
    public static final String CLIENT_ID = "storeforge-platform";
    static final String AMR_CLAIM = "amr";

    private final JwtEncoder encoder;
    private final JwtKeys keys;
    private final AuthProperties properties;
    private final Clock clock;

    public AccessToken issue(UUID userId, int securityStamp, List<String> amr, Duration ttl) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim("client_id", CLIENT_ID)
                .claim(AccessTokenIssuer.SECURITY_STAMP_CLAIM, securityStamp)
                .claim(AMR_CLAIM, amr)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.activeKey().getKeyID()).type("at+jwt").build();
        return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), ttl.toSeconds());
    }

    /** Not a bean: a second JwtDecoder bean would make the merchant chain's decoder ambiguous. */
    JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(keys.verificationKeys()))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(JwtValidators.createAtJwtValidator()
                .issuer(properties.issuer()).audience(AUDIENCE).clientId(CLIENT_ID).build());
        return decoder;
    }
}
