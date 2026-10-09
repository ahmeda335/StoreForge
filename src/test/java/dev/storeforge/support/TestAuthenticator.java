package dev.storeforge.support;

import java.nio.ByteBuffer;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Plays the admin's phone: computes TOTP codes from the enrolled base32 secret. Written separately
 * from the production implementation on purpose, so a shared bug can't make both agree.
 */
public final class TestAuthenticator {

    private final byte[] secret;

    public TestAuthenticator(String base32Secret) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        long buffer = 0;
        int bits = 0;
        for (char c : base32Secret.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((int) (buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        this.secret = out.toByteArray();
    }

    /** Code for the current 30-second window shifted by {@code stepOffset} windows. */
    public String code(int stepOffset) {
        long step = Instant.now().getEpochSecond() / 30 + stepOffset;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[19] & 0xf;
            int value = ((h[o] & 0x7f) << 24 | (h[o + 1] & 0xff) << 16 | (h[o + 2] & 0xff) << 8 | (h[o + 3] & 0xff)) % 1_000_000;
            return String.format("%06d", value);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
