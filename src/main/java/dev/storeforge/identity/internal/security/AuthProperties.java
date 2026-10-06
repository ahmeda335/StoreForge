package dev.storeforge.identity.internal.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.auth.*}: validated at startup, so a bad configuration fails fast. */
@Validated
@ConfigurationProperties("storeforge.auth")
public record AuthProperties(
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration emailVerificationTtl,
        @NotNull Duration passwordResetTtl,
        /* Active signing key as a private EC P-256 JWK (JSON). Blank = generate an ephemeral key. */
        String signingJwk,
        /* Additional PUBLIC keys (JWK Set JSON) still accepted for verification during key rotation. */
        String previousPublicJwks,
        boolean allowEphemeralSigningKey,
        @NotNull List<String> allowedOrigins,
        @NotBlank String appBaseUrl,
        @Valid @NotNull Lockout lockout,
        @Valid @NotNull RateLimits rateLimits) {

    public record Lockout(@Min(1) int threshold, @NotNull Duration baseDuration, @NotNull Duration maxDuration) {
    }

    /** Requests per minute. */
    public record RateLimits(@Min(1) long perIp, @Min(1) long perEmail) {
    }
}
