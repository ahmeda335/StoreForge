package dev.storeforge.developer.internal.credentials;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Token formats: {@code sfk_} API keys, {@code sfat_} app access tokens (P12b), then 256 random bits.
 * The prefix tells a human (and secret scanners such as GitHub's) what leaked, and lets
 * authentication reject garbage without a database lookup.
 */
public final class ApiTokens {

    public static final String API_KEY_PREFIX = "sfk_";
    public static final String APP_TOKEN_PREFIX = "sfat_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiTokens() {
    }

    public static String newToken(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What may be shown later: the type prefix and 4 characters. */
    public static String displayPrefix(String token) {
        int typeEnd = token.indexOf('_') + 1;
        return token.substring(0, Math.min(token.length(), typeEnd + 4));
    }

    public static boolean looksValid(String token) {
        return (token.startsWith(API_KEY_PREFIX) || token.startsWith(APP_TOKEN_PREFIX)) && token.length() < 100;
    }
}
