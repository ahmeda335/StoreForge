package dev.storeforge.payments.internal;

import dev.storeforge.payments.internal.provider.FakePaymentProvider;
import dev.storeforge.payments.internal.provider.PaymentGateway;
import dev.storeforge.payments.internal.provider.PaymentProvider;
import dev.storeforge.payments.internal.provider.ProviderUnavailableException;
import dev.storeforge.payments.internal.provider.StripePaymentProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Exactly one provider adapter is active ({@code storeforge.payments.provider}), always behind the gateway. */
@Configuration(proxyBeanMethods = false)
@Slf4j
class PaymentsConfiguration {

    @Bean
    @ConditionalOnProperty(name = "storeforge.payments.provider", havingValue = FakePaymentProvider.NAME, matchIfMissing = true)
    FakePaymentProvider fakePaymentProvider(PaymentsProperties properties, JsonMapper jsonMapper, Clock clock) {
        log.warn("Using the FAKE payment provider: no real money moves, and anyone can confirm payments through "
                + "/fake-provider/**. Configure storeforge.payments.provider=stripe outside local development.");
        String url = properties.fake().webhookUrl();
        return new FakePaymentProvider(properties.fake().webhookSecret(), url == null || url.isBlank() ? null : URI.create(url),
                jsonMapper, clock, properties.webhookTolerance());
    }

    @Bean
    @ConditionalOnProperty(name = "storeforge.payments.provider", havingValue = StripePaymentProvider.NAME)
    StripePaymentProvider stripePaymentProvider(PaymentsProperties properties, ObservationRegistry observations, JsonMapper jsonMapper,
                                                Clock clock) {
        var stripe = properties.stripe();
        return new StripePaymentProvider(stripe.baseUrl(), stripe.secretKey(), stripe.apiVersion(), stripe.webhookSecret(),
                stripe.connectTimeout(), stripe.readTimeout(), properties.webhookTolerance(), observations, jsonMapper, clock);
    }

    @Bean
    PaymentGateway paymentGateway(PaymentProvider provider, PaymentsProperties properties, MeterRegistry meterRegistry) {
        var settings = properties.circuitBreaker();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(settings.slidingWindow())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.openFor())
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(ProviderUnavailableException.class)   // declines are not failures of the provider
                .build());
        // resilience4j_circuitbreaker_state{name="payment-provider",state=...}, calls, failure rate
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return new PaymentGateway(provider, registry.circuitBreaker("payment-provider"), meterRegistry);
    }
}
