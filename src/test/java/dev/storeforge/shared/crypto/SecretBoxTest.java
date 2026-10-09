package dev.storeforge.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    static final byte[] KEY = new byte[32];
    static final SecretBox BOX = new SecretBox(KEY);

    @Test
    void roundTripsAndNeverProducesTheSameCiphertextTwice() {
        String sealed = BOX.seal("whsec_abc", "subscription-1");
        assertThat(sealed).startsWith("v1:").doesNotContain("whsec_abc");
        assertThat(BOX.seal("whsec_abc", "subscription-1")).as("random IV").isNotEqualTo(sealed);
        assertThat(BOX.open(sealed, "subscription-1")).isEqualTo("whsec_abc");
    }

    /** GCM authenticates: tampering, copying to another row, or the wrong key all fail loudly. */
    @Test
    void refusesTamperedMovedOrForeignCiphertexts() {
        String sealed = BOX.seal("whsec_abc", "subscription-1");
        byte[] raw = Base64.getDecoder().decode(sealed.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;

        assertThatThrownBy(() -> BOX.open(tampered, "subscription-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BOX.open(sealed, "subscription-2")).as("copied to another subscription").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretBox(otherKey).open(sealed, "subscription-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretBox(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }
}
