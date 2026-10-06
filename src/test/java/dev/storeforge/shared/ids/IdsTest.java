package dev.storeforge.shared.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void producesVersion7RfcVariantUuids() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestampSoIdsSortByCreationTime() {
        Instant earlier = Instant.parse("2026-10-06T10:00:00Z");
        UUID first = Ids.newId(Clock.fixed(earlier, ZoneOffset.UTC));
        UUID second = Ids.newId(Clock.fixed(earlier.plusMillis(1), ZoneOffset.UTC));

        assertThat(first.getMostSignificantBits() >>> 16).isEqualTo(earlier.toEpochMilli());
        assertThat(first.toString()).isLessThan(second.toString());
    }

    @Test
    void idsCreatedInTheSameMillisecondAreStillUnique() {
        Clock frozen = Clock.fixed(Instant.now(), ZoneOffset.UTC);

        assertThat(Ids.newId(frozen)).isNotEqualTo(Ids.newId(frozen));
    }
}
