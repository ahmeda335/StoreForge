package dev.storeforge.webhooks.internal.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code X-StoreForge-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>}, one
 * {@code v1=} per active secret (two during a rotation overlap). The same scheme our payment
 * provider uses towards us, so receivers can use the same verification logic.
 */
public final class WebhookSignature {

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSignature() {
    }

    public static String header(List<String> secrets, long timestamp, String body) {
        return secrets.stream().map(secret -> "v1=" + hmac(secret, timestamp + "." + body)).collect(Collectors.joining(","));
    }

    /** {@code whsec_} + 32 random bytes, URL-safe base64. Shown to the merchant once. */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
