package dev.storeforge.payments.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.jobs.Jobs;
import dev.storeforge.orders.Orders;
import dev.storeforge.payments.internal.PaymentsProperties;
import dev.storeforge.payments.internal.domain.Payment;
import dev.storeforge.payments.internal.domain.PaymentExceptions;
import dev.storeforge.payments.internal.domain.Refund;
import dev.storeforge.payments.internal.persistence.PaymentRecords;
import dev.storeforge.payments.internal.persistence.PaymentRepository;
import dev.storeforge.payments.internal.persistence.RefundRepository;
import dev.storeforge.payments.internal.provider.PaymentGateway;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderEvent;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderRefund;
import dev.storeforge.payments.internal.provider.PaymentProvider.RefundStatus;
import dev.storeforge.payments.internal.provider.ProviderRejectedException;
import dev.storeforge.payments.internal.provider.ProviderUnavailableException;
import dev.storeforge.shared.idempotency.IdempotencyExceptions;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Refunds. A refund is decided and recorded (PENDING) under the payment row lock, together with a
 * durable retry job, and committed BEFORE the provider is called. The lock makes concurrent
 * refunds of one payment queue up, and each sees the others' pending amounts: their sum can never
 * exceed what was captured (and the database CHECK would refuse it anyway).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RefundService {

    public static final String JOB = "payments.refund";

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentRecords records;
    private final Orders orders;
    private final PaymentGateway gateway;
    private final Jobs jobs;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final PaymentsProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public record Requested(Refund refund, String currency, boolean replayed) {
    }

    record Work(Refund.Status status, long amountMinor, @Nullable String providerRefundId, String providerPaymentId, UUID paymentId) {
    }

    /**
     * Merchant refund. {@code Idempotency-Key} is required: a retried request (timeout, double click)
     * returns the first refund instead of refunding twice.
     */
    public Requested request(UUID userId, StoreId storeId, UUID orderId, String amount, String reason, @Nullable String key) {
        Requested requested = tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.ORDERS_REFUND);
            orders.paymentTarget(storeId, orderId);   // 404 for an unknown order
            if (key == null || key.isBlank() || key.length() > 255) {
                throw new IdempotencyExceptions.KeyRequired();
            }
            Payment payment = payments.lockByOrder(storeId.value(), orderId).orElseThrow(PaymentExceptions.PaymentNotFound::new);
            long amountMinor = parse(amount, payment.currency());
            Optional<Refund> existing = refunds.findByStoreIdAndIdempotencyKey(storeId.value(), "merchant:" + key);
            if (existing.isPresent()) {
                Refund previous = existing.get();
                if (!previous.paymentId().equals(payment.id()) || previous.amountMinor() != amountMinor || !previous.reason().equals(reason)) {
                    throw new IdempotencyExceptions.KeyReused();
                }
                return new Requested(previous, payment.currency(), true);
            }
            if (!payment.refundable()) {
                throw new PaymentExceptions.NotRefundable();
            }
            long available = payment.refundableMinor(refunds.pendingTotal(storeId.value(), payment.id()));
            if (amountMinor > available) {
                throw new PaymentExceptions.RefundExceedsAvailable(
                        Money.ofMinor(available, Currency.getInstance(payment.currency())).toDecimalString());
            }
            Instant now = clock.instant();
            Refund refund = refunds.save(Refund.request(storeId, payment.id(), amountMinor, reason, "merchant:" + key, userId, now));
            // Durable safety net, due after the synchronous attempt below has had its chance.
            jobs.enqueue(storeId, JOB, refund.id().toString(), Map.of("refundId", refund.id().toString()),
                    now.plus(properties.refundRetryDelay()));
            audit.recordUserAction(storeId, userId, "payment.refund_requested", "order", orderId.toString(),
                    Map.of("refundId", refund.id().toString(), "amount", amountMinor, "reason", reason));
            return new Requested(refund, payment.currency(), false);
        });
        if (!requested.replayed()) {
            try {
                execute(storeId, requested.refund().id());
            } catch (ProviderUnavailableException e) {
                log.atInfo().addKeyValue("refundId", requested.refund().id())
                        .log("Refund not confirmed by the provider yet; the queued job will retry with the same key");
            }
        }
        Refund current = tenant.inStore(storeId, () -> refunds.findByStoreIdAndId(storeId.value(), requested.refund().id()).orElseThrow());
        return new Requested(current, requested.currency(), requested.replayed());
    }

    /**
     * Money the order can't keep. Called inside the capture transaction, so "captured" and "must be
     * given back" commit together. One compensation per payment (unique key).
     */
    void compensate(StoreId storeId, Payment payment) {
        String key = "compensation:" + payment.id();
        if (refunds.findByStoreIdAndIdempotencyKey(storeId.value(), key).isPresent()) {
            return;
        }
        Refund refund = refunds.save(Refund.request(storeId, payment.id(), payment.capturedMinor(), Refund.COMPENSATION, key, null,
                clock.instant()));
        jobs.enqueue(storeId, JOB, refund.id().toString(), Map.of("refundId", refund.id().toString()));
    }

    /**
     * Drives one refund towards a final state: refund at the provider (or, once the provider knows
     * it, ask for its status). Safe to run any number of times, concurrently too: the provider
     * deduplicates on the key, and the result is applied under locks only while still PENDING.
     *
     * @return the refund's status afterwards
     */
    public Refund.Status execute(StoreId storeId, UUID refundId) {
        Work work = tenant.inStore(storeId, () -> {
            Refund refund = refunds.findByStoreIdAndId(storeId.value(), refundId).orElseThrow();
            Payment payment = payments.findByStoreIdAndId(storeId.value(), refund.paymentId()).orElseThrow();
            return new Work(refund.status(), refund.amountMinor(), refund.providerRefundId(), payment.providerPaymentId(), payment.id());
        });
        if (work.status() != Refund.Status.PENDING) {
            return work.status();
        }
        String key = "refund:" + refundId;
        tenant.runInStore(storeId, () -> records.beginOperation(storeId, work.paymentId(), "REFUND", key, work.amountMinor(), clock.instant()));
        ProviderRefund result;
        try {
            result = work.providerRefundId() != null
                    ? gateway.retrieveRefund(work.providerRefundId())
                    : gateway.refund(work.providerPaymentId(), work.amountMinor(), storeId.value(), refundId, key);
        } catch (ProviderUnavailableException e) {
            tenant.runInStore(storeId, () -> records.finishOperation(storeId, key, e.outcomeUnknown() ? "UNKNOWN" : "FAILED", null,
                    e.outcomeUnknown() ? "outcome_unknown" : "provider_unavailable", clock.instant()));
            throw e;
        } catch (ProviderRejectedException e) {
            tenant.runInStore(storeId, () -> records.finishOperation(storeId, key, "FAILED", null, e.code(), clock.instant()));
            return applyResult(storeId, refundId, null, RefundStatus.FAILED).orElseThrow();
        }
        tenant.runInStore(storeId, () -> records.finishOperation(storeId, key, "SUCCEEDED", result.id(), null, clock.instant()));
        return applyResult(storeId, refundId, result.id(), result.status()).orElseThrow();
    }

    /** A refund webhook. @return false if no refund matches */
    boolean applyProviderUpdate(StoreId storeId, ProviderEvent event) {
        if (event.refundStatus() == null) {
            return true;
        }
        UUID refundId = event.refundId();
        if (refundId == null && event.providerRefundId() != null) {
            refundId = tenant.inStore(storeId, () -> refunds.idByProviderRefundId(storeId.value(), event.providerRefundId())).orElse(null);
        }
        return refundId != null && applyResult(storeId, refundId, event.providerRefundId(), event.refundStatus()).isPresent();
    }

    private Optional<Refund.Status> applyResult(StoreId storeId, UUID refundId, @Nullable String providerRefundId, RefundStatus status) {
        return tenant.inStore(storeId, () -> {
            Optional<UUID> paymentId = refunds.paymentIdOf(storeId.value(), refundId);
            if (paymentId.isEmpty()) {
                return Optional.<Refund.Status>empty();
            }
            Payment payment = payments.lock(storeId.value(), paymentId.get()).orElseThrow();   // payment first, then refund
            Refund refund = refunds.lock(storeId.value(), refundId).orElseThrow();
            Instant now = clock.instant();
            if (providerRefundId != null) {
                refund.attachProviderRefund(providerRefundId, now);
            }
            if (status == RefundStatus.SUCCEEDED && refund.succeed(now)) {
                payment.recordRefund(refund.amountMinor(), now);
                payments.flush();
                orders.applyRefund(storeId, payment.orderId(), refund.id(), refund.amountMinor(), refund.reason(), refund.compensation());
                meterRegistry.counter("storeforge.payments.refunds", "outcome", "succeeded", "kind",
                        refund.compensation() ? "compensation" : "merchant").increment();
            } else if (status == RefundStatus.FAILED && refund.fail(now)) {
                meterRegistry.counter("storeforge.payments.refunds", "outcome", "failed", "kind",
                        refund.compensation() ? "compensation" : "merchant").increment();
                log.atWarn().addKeyValue("refundId", refund.id()).log("Refund failed at the provider");
            }
            return Optional.of(refund.status());
        });
    }

    private static long parse(String amount, String currency) {
        try {
            Money money = Money.parse(amount, Currency.getInstance(currency));
            if (money.amountMinor() <= 0) {
                throw new PaymentExceptions.InvalidAmount();
            }
            return money.amountMinor();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new PaymentExceptions.InvalidAmount();
        }
    }
}
