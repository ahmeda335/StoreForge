package dev.storeforge.identity.internal.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {

    static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238 appendix B, SHA-1 column (8 digits). */
    @ParameterizedTest
    @CsvSource({"59, 94287082", "1111111109, 07081804", "1111111111, 14050471", "1234567890, 89005924", "2000000000, 69279037",
            "20000000000, 65353130"})
    void matchesTheRfcTestVectors(long epochSecond, String expected) {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(epochSecond)), 8)).isEqualTo(expected);
    }

    @Test
    void acceptsOneStepOfDriftEachWayAndNothingFurther() {
        Instant now = Instant.ofEpochSecond(1_234_567_890);
        long step = Totp.step(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step, 6), now)).hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1, 6), now)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1, 6), now)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2, 6), now)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now)).as("wrong length").isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now)).isEmpty();
    }

    @Test
    void base32AndTheOtpauthUriAreWhatAuthenticatorAppsExpect() {
        assertThat(Base32.encode(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).isEqualTo(RFC_SECRET);
        assertThat(Totp.otpauthUri("StoreForge", "ada@example.test", RFC_SECRET)).isEqualTo(
                "otpauth://totp/StoreForge:ada%40example.test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=StoreForge&algorithm=SHA1&digits=6&period=30");
    }
}
