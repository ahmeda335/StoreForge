package dev.storeforge.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RequestIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {"abcd1234", "6f1c7d0e-8a8b-4b1e-9c1a-0e1f2a3b4c5d", "ORDER-retry-0001"})
    void acceptsIdentifierLikeIds(String incoming) {
        assertThat(RequestIds.acceptIncoming(incoming)).contains(incoming);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "short",                                   // too short to be a meaningful identifier
            "has space inside it",
            "line\nbreak-injection",                   // log forging attempt
            "{\"json\":\"injection\"}",
            "a-very-long-id-that-goes-on-and-on-and-on-and-on-and-on-and-on-and-on"  // > 64 chars
    })
    void rejectsUnsafeOrMalformedIds(String incoming) {
        assertThat(RequestIds.acceptIncoming(incoming)).isEmpty();
    }

    @Test
    void generatedIdsAreAcceptedFormatAndUnique() {
        String first = RequestIds.generate();
        String second = RequestIds.generate();

        assertThat(RequestIds.acceptIncoming(first)).contains(first);
        assertThat(first).isNotEqualTo(second);
    }
}
