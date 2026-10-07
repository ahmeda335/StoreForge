package dev.storeforge.payments.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.payments.*} */
@Validated
@ConfigurationProperties("storeforge.payments")
public record PaymentsProperties(
        /* fake | stripe */
        @NotBlank String provider,
        /* Webhooks signed longer ago than this are refused (replay protection). */
        @NotNull Duration webhookTolerance,
        /* A refund's durable retry runs this long after the synchronous attempt. */
        @NotNull Duration refundRetryDelay,
        @NotNull @Valid Reconciliation reconciliation,
        @NotNull @Valid CircuitBreaker circuitBreaker,
        @NotNull @Valid Fake fake,
        @NotNull @Valid Stripe stripe) {

    public record Reconciliation(@NotNull Duration interval, @NotNull Duration staleAfter, @NotNull Duration providerEventRetention) {
    }

    public record CircuitBreaker(@Min(1) int failureRateThreshold, @Min(1) int slidingWindow, @Min(1) int minimumCalls,
                                 @NotNull Duration openFor) {
    }

    public record Fake(@NotBlank String webhookSecret, @Nullable String webhookUrl) {
    }

    public record Stripe(@NotNull URI baseUrl, @Nullable String secretKey, @Nullable String webhookSecret, @Nullable String apiVersion,
                         @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }
}
