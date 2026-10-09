package dev.storeforge.identity.internal.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.platform.*} */
@Validated
@ConfigurationProperties("storeforge.platform")
public record PlatformProperties(
        /* base64 of 32 random bytes; seals TOTP secrets at rest. Its OWN key, not the webhook one. */
        @NotBlank String mfaEncryptionKey,
        /* Lifetime of a platform access token. No refresh: after it expires, the admin steps up again. */
        @NotNull Duration sessionTtl,
        /* Shown in authenticator apps. */
        @NotBlank String totpIssuer) {
}
