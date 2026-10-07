package dev.storeforge.payments.internal.provider;

import dev.storeforge.payments.internal.provider.PaymentProvider.CreatePayment;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderEvent;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderPayment;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderRefund;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * The only way the application talks to the provider: every call goes through a circuit breaker
 * and is timed. When the provider keeps failing, the breaker opens and calls fail immediately
 * (no threads stuck in timeouts, no load piled onto a struggling provider); after a pause a few
 * trial calls decide whether to close it again. Only "unavailable" failures count: a declined
 * card is the provider working correctly.
 */
public class PaymentGateway {

    private final PaymentProvider provider;
    @Getter
    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;

    public PaymentGateway(PaymentProvider provider, CircuitBreaker circuitBreaker, MeterRegistry meterRegistry) {
        this.provider = provider;
        this.circuitBreaker = circuitBreaker;
        this.meterRegistry = meterRegistry;
    }

    public String providerName() {
        return provider.name();
    }

    public ProviderPayment createPayment(CreatePayment request, String idempotencyKey) {
        return call("create_payment", () -> provider.createPayment(request, idempotencyKey));
    }

    public ProviderPayment retrievePayment(String providerPaymentId) {
        return call("retrieve_payment", () -> provider.retrievePayment(providerPaymentId));
    }

    public ProviderPayment cancelPayment(String providerPaymentId, String idempotencyKey) {
        return call("cancel_payment", () -> provider.cancelPayment(providerPaymentId, idempotencyKey));
    }

    public ProviderRefund refund(String providerPaymentId, long amountMinor, UUID storeId, UUID refundId, String idempotencyKey) {
        return call("refund", () -> provider.refund(providerPaymentId, amountMinor, storeId, refundId, idempotencyKey));
    }

    public ProviderRefund retrieveRefund(String providerRefundId) {
        return call("retrieve_refund", () -> provider.retrieveRefund(providerRefundId));
    }

    /** Local work (signature check + parse): no breaker. */
    public ProviderEvent parseWebhook(String rawBody, @Nullable String signatureHeader) {
        return provider.parseWebhook(rawBody, signatureHeader);
    }

    private <T> T call(String operation, Supplier<T> work) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return circuitBreaker.executeSupplier(work);
        } catch (CallNotPermittedException e) {
            outcome = "circuit_open";
            throw new ProviderUnavailableException(operation, false, "circuit breaker open", e);
        } catch (ProviderUnavailableException e) {
            outcome = e.outcomeUnknown() ? "unknown" : "unavailable";
            throw e;
        } catch (ProviderRejectedException e) {
            outcome = "rejected";
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("storeforge.payments.provider.calls", "provider", provider.name(),
                    "operation", operation, "outcome", outcome));
        }
    }
}
