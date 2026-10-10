package dev.storeforge.shared.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.http.*}: limits and policies applied to every request at the edge of the application. */
@Validated
@ConfigurationProperties("storeforge.http")
public record HttpEdgeProperties(
        /* Largest request body accepted; larger ones get 413 before anything parses them. */
        @NotNull DataSize maxRequestBody,
        /* Browser origins allowed to call /auth and /admin cross-origin (the merchant admin frontend). */
        @NotNull List<String> adminOrigins,
        /* Requests processed at once per instance; beyond it, 503 after concurrencyWait (load shedding, EXP-023). */
        @Min(1) int maxConcurrentRequests,
        @NotNull Duration concurrencyWait) {
}
