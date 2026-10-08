package dev.storeforge.catalog.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageSignaturesTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void eachAcceptedFormatIsRecognisedByItsOwnSignatureOnly() {
        assertThat(ImageSignatures.matches("image/png", PNG)).isTrue();
        assertThat(ImageSignatures.matches("image/jpeg", JPEG)).isTrue();
        assertThat(ImageSignatures.matches("image/webp", WEBP)).isTrue();
        assertThat(ImageSignatures.matches("image/png", JPEG)).as("declared PNG, actually JPEG").isFalse();
        assertThat(ImageSignatures.matches("image/webp", "RIFF\0\0\0\0WAVE".getBytes(StandardCharsets.US_ASCII))).as("RIFF but audio").isFalse();
    }

    @Test
    void scriptsAndTruncatedFilesAreNotImages() {
        byte[] html = "<svg onload=alert(1)>".getBytes(StandardCharsets.US_ASCII);
        assertThat(ImageSignatures.matches("image/png", html)).isFalse();
        assertThat(ImageSignatures.matches("image/svg+xml", html)).as("not an accepted type at all").isFalse();
        assertThat(ImageSignatures.matches("image/png", new byte[] {(byte) 0x89, 'P'})).as("shorter than the signature").isFalse();
        assertThat(ImageSignatures.matches("image/webp", new byte[] {'R', 'I', 'F', 'F'})).isFalse();
    }
}
