package dev.storeforge.developer.internal.ratelimit;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 5 minutes: Redis usage counters → app_usage_hourly (idempotent upsert of absolute values). */
@Component
@RequiredArgsConstructor
class UsageRollupJob {

    private final UsageCounters usage;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    void run() {
        usage.rollUp(clock.instant());
    }
}
