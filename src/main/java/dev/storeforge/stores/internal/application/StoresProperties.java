package dev.storeforge.stores.internal.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("storeforge.stores")
record StoresProperties(@NotBlank String platformDomain, @NotNull Duration invitationTtl, @NotBlank String appBaseUrl) {
}
