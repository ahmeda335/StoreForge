package dev.storeforge.identity.internal.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing keys for access tokens.
 *
 * <ul>
 *   <li>One ACTIVE private key signs new tokens; its {@code kid} goes into the JWT header.</li>
 *   <li>PREVIOUS public keys are still accepted for verification, so tokens signed before a key
 *       rotation stay valid until they expire (at most one access-token TTL).</li>
 * </ul>
 *
 * Rotation: generate a new key → configure it as active and move the old public key to
 * {@code previous-public-jwks} → deploy → after one access-token TTL, drop the old key.
 *
 * <p>ES256 (ECDSA P-256) is asymmetric: verifiers only need the public key (published at the JWKS
 * endpoint), so a future separately deployed service can verify tokens without being able to mint them.
 */
public final class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    private final ECKey activeKey;
    private final JWKSet verificationKeys;

    private JwtKeys(ECKey activeKey, List<JWK> previousPublicKeys) {
        this.activeKey = activeKey;
        List<JWK> all = new ArrayList<>();
        all.add(activeKey.toPublicJWK());
        all.addAll(previousPublicKeys);
        this.verificationKeys = new JWKSet(all);
    }

    public static JwtKeys from(AuthProperties properties) {
        try {
            ECKey active;
            if (properties.signingJwk() == null || properties.signingJwk().isBlank()) {
                if (!properties.allowEphemeralSigningKey()) {
                    throw new IllegalStateException("storeforge.auth.signing-jwk is required (ephemeral keys are disabled)");
                }
                log.warn("Using an EPHEMERAL JWT signing key: tokens become invalid on restart and differ between "
                        + "instances. Configure storeforge.auth.signing-jwk outside local development.");
                active = new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).keyIDFromThumbprint(true).generate();
            } else {
                active = ECKey.parse(properties.signingJwk());
                if (!active.isPrivate() || !Curve.P_256.equals(active.getCurve()) || active.getKeyID() == null) {
                    throw new IllegalStateException("storeforge.auth.signing-jwk must be a private P-256 EC JWK with a kid");
                }
            }
            List<JWK> previous = properties.previousPublicJwks() == null || properties.previousPublicJwks().isBlank()
                    ? List.of()
                    : JWKSet.parse(properties.previousPublicJwks()).getKeys().stream().map(JWK::toPublicJWK).toList();
            return new JwtKeys(active, previous);
        } catch (ParseException | JOSEException e) {
            throw new IllegalStateException("Invalid JWT key configuration", e);
        }
    }

    public ECKey activeKey() {
        return activeKey;
    }

    /** Public keys only: safe to publish. */
    public JWKSet verificationKeys() {
        return verificationKeys;
    }
}
