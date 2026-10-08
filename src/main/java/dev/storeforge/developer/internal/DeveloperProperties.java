package dev.storeforge.developer.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.developer.*} and {@code storeforge.redis.*} */
@Validated
@ConfigurationProperties("storeforge.developer")
public record DeveloperProperties(
        @NotNull @Valid Bucket perCredential,
        /* All credentials of one store together: one store's integrations can't swamp the platform. */
        @NotNull @Valid Bucket perStore,
        @Min(1) int maxApiKeysPerStore,
        @NotNull Duration usageRetentionInRedis) {

    /** Token bucket: {@code capacity} = burst, refilled at {@code refillPerSecond}. */
    public record Bucket(@Min(1) int capacity, @Min(1) int refillPerSecond) {
    }

    @Validated
    @ConfigurationProperties("storeforge.redis")
    public record Redis(@NotBlank String url, @NotNull Duration commandTimeout, @NotNull Duration reconnectBackoff) {
    }
}
