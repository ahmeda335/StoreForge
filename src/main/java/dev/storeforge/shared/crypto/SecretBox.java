package dev.storeforge.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts secrets the application must be able to read back (webhook signing secrets, TOTP
 * secrets), so a database dump doesn't reveal them: AES-256-GCM with a key that lives outside the
 * database (secret store / environment). GCM also authenticates: a modified ciphertext fails to open.
 * Each use has its OWN key.
 *
 * <p>The owning row's id is bound as associated data, so a ciphertext copied into another row
 * does not decrypt there. Format: {@code v1:base64(iv ‖ ciphertext+tag)};
 * the version prefix leaves room to rotate the key later.
 */
public final class SecretBox {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("An encryption key must be 32 bytes (base64 of 32 random bytes)");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    public String seal(String plaintext, String associatedData) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String open(String sealed, String associatedData) {
        if (!sealed.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown secret format");
        }
        byte[] data = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret could not be decrypted (wrong key or tampered data)", e);
        }
    }
}
