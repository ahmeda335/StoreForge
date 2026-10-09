package dev.storeforge.shared.redis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.redis.*} */
@Validated
@ConfigurationProperties("storeforge.redis")
public record RedisProperties(@NotBlank String url, @NotNull Duration commandTimeout, @NotNull Duration reconnectBackoff) {
}
