package dev.storeforge.jobs.internal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.jobs.*} */
@Validated
@ConfigurationProperties("storeforge.jobs")
record JobsProperties(
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        /* How long a claimed job belongs to its worker. A crashed worker's jobs are reclaimed after it. */
        @NotNull Duration lease,
        @Min(1) int maxAttempts,
        @NotNull Duration backoffBase,
        @NotNull Duration backoffCap,
        @NotNull Duration retention) {
}
