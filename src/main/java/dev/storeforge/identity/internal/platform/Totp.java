package dev.storeforge.identity.internal.platform;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238 over RFC 4226 HOTP): HMAC-SHA1, 30-second steps, 6
 * digits, which is what every authenticator app implements. Pure functions; replay protection
 * (a step is accepted once) is the caller's job, because it needs state.
 */
final class Totp {

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    /** One step either side: tolerates ~30 s of clock drift between phone and server. */
    static final int WINDOW = 1;

    private Totp() {
    }

    static byte[] newSecret(SecureRandom random) {
        byte[] secret = new byte[20];   // 160 bits, as RFC 4226 recommends for SHA-1
        random.nextBytes(secret);
        return secret;
    }

    static long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
    }

    static String code(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;   // dynamic truncation
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%0" + digits + "d", binary % (int) Math.pow(10, digits));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The step the code belongs to, if it is valid now (within the window); constant-time comparison. */
    static OptionalLong verify(byte[] secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = step(now);
        OptionalLong matched = OptionalLong.empty();
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            if (MessageDigest.isEqual(code(secret, step, DIGITS).getBytes(), code.getBytes()) && matched.isEmpty()) {
                matched = OptionalLong.of(step);
            }
        }
        return matched;
    }

    /** The URI authenticator apps import (usually from a QR code). */
    static String otpauthUri(String issuer, String account, byte[] secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
