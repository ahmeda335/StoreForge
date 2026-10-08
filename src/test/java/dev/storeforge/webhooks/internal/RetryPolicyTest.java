package dev.storeforge.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.webhooks.internal.delivery.RetryPolicy;
import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    static final List<Duration> SCHEDULE = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2),
            Duration.ofHours(6), Duration.ofHours(12), Duration.ofHours(24), Duration.ofHours(24));
    static final RetryPolicy POLICY = new RetryPolicy(SCHEDULE, 9, 3);
    static final RandomGenerator MIDDLE = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public double nextDouble() {
            return 0.5;   // no jitter
        }
    };

    @Test
    void followsTheScheduleForAboutThreeDaysThenGivesUp() {
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < 9; attempt++) {
            var decision = POLICY.afterFailure(attempt, 503, null, MIDDLE);
            assertThat(decision.retry()).isTrue();
            assertThat(decision.delay()).isEqualTo(SCHEDULE.get(attempt - 1));
            total = total.plus(decision.delay());
        }
        assertThat(POLICY.afterFailure(9, 503, null, MIDDLE).retry()).isFalse();
        assertThat(total).isBetween(Duration.ofHours(60), Duration.ofHours(72));
    }

    @Test
    void clientErrorsGiveUpSoonerButTimeoutsAndRateLimitsDont() {
        assertThat(POLICY.afterFailure(3, 404, null, MIDDLE).retry()).isFalse();
        assertThat(POLICY.afterFailure(3, 408, null, MIDDLE).retry()).isTrue();
        assertThat(POLICY.afterFailure(3, 429, null, MIDDLE).retry()).isTrue();
        assertThat(POLICY.afterFailure(3, null, null, MIDDLE).retry()).as("no response: timeout").isTrue();
    }

    @Test
    void retryAfterMeansNotBeforeAndIsCappedAtADay() {
        assertThat(POLICY.afterFailure(1, 429, Duration.ofMinutes(10), MIDDLE).delay()).isEqualTo(Duration.ofMinutes(10));
        assertThat(POLICY.afterFailure(1, 429, Duration.ofSeconds(1), MIDDLE).delay()).as("never sooner than the schedule")
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(POLICY.afterFailure(1, 503, Duration.ofDays(30), MIDDLE).delay()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void jitterStaysWithinTenPercent() {
        RandomGenerator low = () -> 0;
        assertThat(POLICY.afterFailure(2, 500, null, low).delay()).isEqualTo(Duration.ofSeconds(270));
    }
}
