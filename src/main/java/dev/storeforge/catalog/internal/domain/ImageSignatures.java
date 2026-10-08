package dev.storeforge.catalog.internal.domain;

import java.util.Arrays;
import java.util.Set;

/**
 * What a file IS, from its first bytes ("magic numbers"), never from the name or the declared
 * Content-Type: both come from the client. Only raster formats browsers render safely; SVG is
 * excluded on purpose (it is XML that can carry scripts).
 */
public final class ImageSignatures {

    public static final Set<String> ACCEPTED = Set.of("image/jpeg", "image/png", "image/webp");
    /** Enough bytes to recognise every accepted format. */
    public static final int PREFIX_LENGTH = 12;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    private ImageSignatures() {
    }

    public static boolean matches(String contentType, byte[] prefix) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(prefix, 0, JPEG);
            case "image/png" -> startsWith(prefix, 0, PNG);
            case "image/webp" -> startsWith(prefix, 0, RIFF) && startsWith(prefix, 8, WEBP);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] data, int offset, byte[] expected) {
        return data.length >= offset + expected.length && Arrays.equals(data, offset, offset + expected.length, expected, 0, expected.length);
    }
}
