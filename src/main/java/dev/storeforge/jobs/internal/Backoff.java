package dev.storeforge.jobs.internal;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with jitter: base · 2^(attempt-1), capped, plus a random 0..base.
 * Exponential: a struggling dependency gets more breathing room after each failure.
 * Jitter: 1,000 jobs that failed together (provider outage) don't all retry in the same second
 * (thundering herd) when it comes back.
 */
final class Backoff {

    private Backoff() {
    }

    static Duration delay(int attempt, Duration base, Duration cap, RandomGenerator random) {
        long exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long millis = Math.min(base.toMillis() * (1L << exponent), cap.toMillis());
        return Duration.ofMillis(millis + random.nextLong(base.toMillis() + 1));
    }
}
