package dev.storeforge.identity.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"Alice@Example.COM", " alice@example.com ", "ALICE@EXAMPLE.COM"})
    void normalizesCaseAndWhitespace(String raw) {
        assertThat(EmailAddress.of(raw).value()).isEqualTo("alice@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"no-at-sign", "two@@example.com", "spaces in@example.com", "missing@tld"})
    void rejectsMalformedAddresses(String raw) {
        assertThatThrownBy(() -> EmailAddress.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {255, 1000})
    void rejectsAddressesLongerThan254Characters(int length) {
        String raw = "a".repeat(length - "@example.com".length()) + "@example.com";

        assertThatThrownBy(() -> EmailAddress.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
