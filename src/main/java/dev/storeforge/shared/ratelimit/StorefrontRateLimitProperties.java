package dev.storeforge.shared.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.storefront.rate-limits.*}: per client address, shared across instances. */
@Validated
@ConfigurationProperties("storeforge.storefront.rate-limits")
public record StorefrontRateLimitProperties(
        /* Every storefront request. */
        @NotNull @Valid Spec general,
        /* POST /carts: anonymous carts are free to create, so creating millions must not be. */
        @NotNull @Valid Spec cartCreation,
        /* POST /checkouts/{id}/completion: card testing runs through checkout completion. */
        @NotNull @Valid Spec checkoutCompletion,
        /* PUT /checkouts/{id}/discount: guessing discount codes. */
        @NotNull @Valid Spec discountAttempts,
        /* GET /search: the most expensive storefront query. */
        @NotNull @Valid Spec search) {

    public record Spec(@Min(1) long capacity, @NotNull Duration period) {
    }
}
