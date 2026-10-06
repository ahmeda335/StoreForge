package dev.storeforge.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import dev.storeforge.identity.internal.security.JwtKeys;
import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.AuthFlows.Session;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Forged and malformed tokens must all be rejected (RFC 8725, "JWT Best Current Practices").
 * Each case starts from a token that WOULD be valid and changes exactly one thing.
 */
class AccessTokenSecurityIT extends IntegrationTest {

    @Autowired
    JwtKeys keys;

    AuthFlows auth;
    Session session;
    String userId;

    @BeforeEach
    void signedInUser() throws Exception {
        auth = new AuthFlows(api, mailbox);
        session = auth.newVerifiedSession(uniqueEmail());
        userId = SignedJWT.parse(session.accessToken()).getJWTClaimsSet().getSubject();
    }

    @Test
    void validTokenGrantsAccess() {
        HttpResponse<String> response = auth.me(session.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.parse(response.body()).get("id").asString()).isEqualTo(userId);
    }

    @Test
    void sanityCheckOurOwnForgeryHelperProducesAcceptedTokens() throws Exception {
        assertThat(auth.me(sign(keys.activeKey(), validClaims().build(), "at+jwt")).statusCode()).isEqualTo(200);
    }

    @Test
    void missingTokenIsRejectedWithProblemDetailsAndChallenge() {
        HttpResponse<String> response = api.get("/admin/v1/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(value -> assertThat(value).startsWith("Bearer"));
        assertThat(Json.parse(response.body()).get("code").asString()).isEqualTo("unauthenticated");
        assertThat(Json.parse(response.body()).has("requestId")).isTrue();
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        String token = new PlainJWT(validClaims().build()).serialize();

        assertRejected(token);
    }

    @Test
    void tokenSignedWithAnotherKeyUsingOurKidIsRejected() throws Exception {
        ECKey attackerKey = new ECKeyGenerator(Curve.P_256).keyID(keys.activeKey().getKeyID()).generate();

        assertRejected(sign(attackerKey, validClaims().build(), "at+jwt"));
    }

    @Test
    void hmacTokenKeyedWithOurPublicKeyIsRejected() throws Exception {
        // Classic algorithm-confusion attack: sign with HS256 using the (public!) key bytes as the secret.
        byte[] publicKeyAsSecret = keys.activeKey().toPublicJWK().toJSONString().getBytes();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(new JOSEObjectType("at+jwt")).keyID(keys.activeKey().getKeyID()).build(), validClaims().build());
        jwt.sign(new MACSigner(publicKeyAsSecret));

        assertRejected(jwt.serialize());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        assertRejected(sign(keys.activeKey(), validClaims().issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(900))).build(), "at+jwt"));
    }

    @Test
    void wrongAudienceIssuerOrTypeIsRejected() throws Exception {
        assertRejected(sign(keys.activeKey(), validClaims().audience("storefront").build(), "at+jwt"));
        assertRejected(sign(keys.activeKey(), validClaims().issuer("https://evil.example").build(), "at+jwt"));
        assertRejected(sign(keys.activeKey(), validClaims().build(), "JWT"));
    }

    @Test
    void tokenWithStaleSecurityStampIsRejected() throws Exception {
        assertRejected(sign(keys.activeKey(), validClaims().claim("sstamp", 99).build(), "at+jwt"));
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = session.accessToken().split("\\.");
        String otherSubject = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace(userId, UUID.randomUUID().toString()).getBytes());

        assertRejected(parts[0] + "." + otherSubject + "." + parts[2]);
    }

    private JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("https://storeforge.localhost")
                .subject(userId)
                .audience("merchant")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .jwtID(UUID.randomUUID().toString())
                .claim("client_id", "storeforge-merchant-web")
                .claim("sstamp", 0);
    }

    private static String sign(ECKey key, JWTClaimsSet claims, String type) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType(type)).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private void assertRejected(String token) {
        HttpResponse<String> response = auth.me(token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(Json.parse(response.body()).get("code").asString()).isEqualTo("unauthenticated");
    }
}
