package dev.storeforge.storage.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.storage.*} */
@Validated
@ConfigurationProperties("storeforge.storage")
record StorageProperties(
        /* Where the APP reaches the S3 API. */
        @NotNull URI endpoint,
        /* Where CLIENTS reach it, used in presigned URLs (e.g. localhost vs. the Compose service name). Blank: endpoint. */
        @Nullable URI publicEndpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        /* Path-style URLs (endpoint/bucket/key): needed by most self-hosted S3 servers. */
        boolean pathStyle,
        /* Create the bucket at startup if missing (local development and tests; production provisions it). */
        boolean createBucket) {

    URI clientEndpoint() {
        return publicEndpoint == null || publicEndpoint.toString().isBlank() ? endpoint : publicEndpoint;
    }
}
