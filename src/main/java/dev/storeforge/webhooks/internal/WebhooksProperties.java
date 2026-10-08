package dev.storeforge.webhooks.internal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.webhooks.*} */
@Validated
@ConfigurationProperties("storeforge.webhooks")
public record WebhooksProperties(
        @NotBlank String apiVersion,
        @Min(1) int maxSubscriptionsPerStore,
        @NotNull Duration connectTimeout,
        /* Hard deadline for one attempt, including a slowly dripping response. */
        @NotNull Duration requestTimeout,
        /* Delay before attempt 2, 3, ...; the last value repeats. */
        @NotEmpty List<Duration> retrySchedule,
        @Min(1) int maxAttempts,
        /* 4xx other than 408/429 usually means a misconfigured receiver: fewer attempts. */
        @Min(1) int maxAttemptsOnClientError,
        @Min(1) int pauseAfterConsecutiveFailures,
        @Min(1) int maxConnectionsPerEndpoint,
        @NotNull Duration secretOverlap,
        @NotNull Duration retention,
        @Min(0) int responseExcerptBytes,
        /* base64 of 32 random bytes; from the secret store in production. */
        @NotBlank String encryptionKey,
        boolean requireHttps,
        /* Escape hatch for local development and tests ONLY, e.g. 127.0.0.0/8. */
        List<String> allowedPrivateCidrs) {
}
