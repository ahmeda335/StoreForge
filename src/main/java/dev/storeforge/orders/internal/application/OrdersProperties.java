package dev.storeforge.orders.internal.application;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code storeforge.orders.*}: payment window of new orders and the extra stock-hold grace after it. */
@Validated
@ConfigurationProperties("storeforge.orders")
record OrdersProperties(@NotNull Duration paymentWindow, @NotNull Duration reservationGrace) {
}
