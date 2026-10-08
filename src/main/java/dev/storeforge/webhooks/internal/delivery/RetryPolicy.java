package dev.storeforge.webhooks.internal.delivery;

import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * When to try a failed delivery again. Default schedule 1m, 5m, 30m, 2h, 6h, 12h, 24h, 24h: nine
 * attempts over about three days, enough to ride out a receiver's weekend outage. Jitter (±10%)
 * spreads the retries of a big outage. {@code Retry-After} (429/503) is honoured as "not before",
 * capped at a day. Client errors (4xx except 408/429) mostly mean a broken receiver config: fewer attempts.
 */
public record RetryPolicy(List<Duration> schedule, int maxAttempts, int maxAttemptsOnClientError) {

    private static final Duration MAX_DELAY = Duration.ofHours(24);

    public record Decision(boolean retry, Duration delay) {
        static Decision giveUp() {
            return new Decision(false, Duration.ZERO);
        }
    }

    /** @param attempts attempts made so far in this cycle, including the one that just failed */
    public Decision afterFailure(int attempts, @Nullable Integer statusCode, @Nullable Duration retryAfter, RandomGenerator random) {
        int limit = isClientError(statusCode) ? maxAttemptsOnClientError : maxAttempts;
        if (attempts >= limit) {
            return Decision.giveUp();
        }
        Duration base = schedule.get(Math.min(attempts - 1, schedule.size() - 1));
        if (retryAfter != null && retryAfter.compareTo(base) > 0) {
            base = retryAfter.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : retryAfter;
        }
        long jittered = Math.round(base.toMillis() * (0.9 + 0.2 * random.nextDouble()));
        return new Decision(true, Duration.ofMillis(Math.max(jittered, 1)));
    }

    public static boolean isClientError(@Nullable Integer statusCode) {
        return statusCode != null && statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429;
    }
}
