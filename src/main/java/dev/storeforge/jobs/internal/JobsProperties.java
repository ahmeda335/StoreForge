package dev.storeforge.jobs.internal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.jobs.*} */
@Validated
@ConfigurationProperties("storeforge.jobs")
record JobsProperties(
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        /* Jobs running at the same time in one instance (virtual threads; mind the DB pool size). */
        @Min(1) int concurrency,
        /* Bulkheads: at most N concurrent jobs of a type, e.g. webhook deliveries that may each take 10 s. */
        Map<String, Integer> typeConcurrency,
        /* How long a claimed job belongs to its worker. A crashed worker's jobs are reclaimed after it. */
        @NotNull Duration lease,
        @Min(1) int maxAttempts,
        @NotNull Duration backoffBase,
        @NotNull Duration backoffCap,
        @NotNull Duration retention) {

    JobsProperties {
        typeConcurrency = typeConcurrency == null ? Map.of() : Map.copyOf(typeConcurrency);
    }
}
