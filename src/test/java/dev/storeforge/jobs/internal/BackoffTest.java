package dev.storeforge.jobs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class BackoffTest {

    static final Duration BASE = Duration.ofSeconds(5);
    static final Duration CAP = Duration.ofHours(1);
    static final RandomGenerator NO_JITTER = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }
    };

    @Test
    void doublesPerAttemptUpToTheCap() {
        assertThat(Backoff.delay(1, BASE, CAP, NO_JITTER)).isEqualTo(Duration.ofSeconds(5));
        assertThat(Backoff.delay(2, BASE, CAP, NO_JITTER)).isEqualTo(Duration.ofSeconds(10));
        assertThat(Backoff.delay(5, BASE, CAP, NO_JITTER)).isEqualTo(Duration.ofSeconds(80));
        assertThat(Backoff.delay(20, BASE, CAP, NO_JITTER)).isEqualTo(CAP);
        assertThat(Backoff.delay(500, BASE, CAP, NO_JITTER)).as("no overflow").isEqualTo(CAP);
    }

    @Test
    void jitterSpreadsRetriesWithinOneBase() {
        for (int i = 0; i < 1000; i++) {
            Duration delay = Backoff.delay(3, BASE, CAP, RandomGenerator.getDefault());
            assertThat(delay).isBetween(Duration.ofSeconds(20), Duration.ofSeconds(25));
        }
    }
}
