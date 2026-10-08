package dev.storeforge.payments.internal.application;

import dev.storeforge.orders.Orders;
import dev.storeforge.payments.Payments;
import dev.storeforge.payments.internal.domain.Payment;
import dev.storeforge.payments.internal.domain.PaymentExceptions;
import dev.storeforge.payments.internal.domain.Refund;
import dev.storeforge.payments.internal.persistence.PaymentRecords;
import dev.storeforge.payments.internal.persistence.PaymentRecords.Operation;
import dev.storeforge.payments.internal.persistence.PaymentRepository;
import dev.storeforge.payments.internal.persistence.RefundRepository;
import dev.storeforge.payments.internal.provider.PaymentGateway;
import dev.storeforge.payments.internal.provider.PaymentProvider.CreatePayment;
import dev.storeforge.payments.internal.provider.PaymentProvider.EventType;
import dev.storeforge.payments.internal.provider.PaymentProvider.PaymentStatus;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderEvent;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderPayment;
import dev.storeforge.payments.internal.provider.ProviderRejectedException;
import dev.storeforge.payments.internal.provider.ProviderUnavailableException;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The payment lifecycle. The rule that shapes every method: NO provider call inside a database
 * transaction. Each provider interaction is
 *
 * <pre>
 *   tx 1: decide + record intent (payment row, operation IN_PROGRESS)  → COMMIT
 *   provider call (idempotency key from our ids, circuit breaker, timeouts)
 *   tx 2: lock the payment row, apply what the provider said           → COMMIT
 * </pre>
 *
 * A crash between the steps leaves a CREATED/PENDING payment or an IN_PROGRESS/UNKNOWN operation;
 * retries (the customer, the job queue, reconciliation) repeat the call with the same key, so the
 * provider acts at most once.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService implements Payments {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentRecords records;
    private final RefundService refundService;
    private final Orders orders;
    private final PaymentGateway gateway;
    private final StoreAccess access;
    private final TenantTransactions tenant;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    record Prepared(Payment payment, long orderNumber) {
    }

    public record PaymentDetails(Payment payment, List<Refund> refunds, List<Operation> operations) {
    }

    @Override
    public PaymentSession start(StoreId storeId, UUID orderId) {
        Prepared prepared = tenant.inStore(storeId, () -> prepare(storeId, orderId));
        if (prepared.payment().status() != Payment.Status.CREATED) {
            return session(prepared.payment());   // already created: same session (idempotent)
        }
        return session(createAtProvider(storeId, prepared));
    }

    @Override
    public Optional<PaymentSession> find(StoreId storeId, UUID orderId) {
        return tenant.inStore(storeId, () -> payments.findByStoreIdAndOrderId(storeId.value(), orderId).map(PaymentService::session));
    }

    public PaymentDetails details(UUID userId, StoreId storeId, UUID orderId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.ORDERS_READ);
            orders.paymentTarget(storeId, orderId);   // 404 for an unknown order
            Payment payment = payments.findByStoreIdAndOrderId(storeId.value(), orderId).orElseThrow(PaymentExceptions.PaymentNotFound::new);
            return new PaymentDetails(payment, refunds.findByStoreIdAndPaymentIdOrderByCreatedAt(storeId.value(), payment.id()),
                    records.operations(storeId, payment.id()));
        });
    }

    /** tx 1 of "start": one payment per order, amount from the ORDER (never from the client). */
    private Prepared prepare(StoreId storeId, UUID orderId) {
        Orders.PaymentTarget target = orders.paymentTarget(storeId, orderId);
        Optional<Payment> existing = payments.lockByOrder(storeId.value(), orderId);
        if (existing.isEmpty()) {
            if (!target.awaitingPayment()) {
                throw new PaymentExceptions.OrderNotAwaitingPayment();
            }
            records.insertIfAbsent(Payment.create(storeId, orderId, gateway.providerName(), target.totalMinor(), target.currency(),
                    clock.instant()));
            existing = payments.lockByOrder(storeId.value(), orderId);
        }
        Payment payment = existing.orElseThrow();
        if (payment.status() == Payment.Status.CANCELLED || payment.status() == Payment.Status.FAILED
                || payment.status() == Payment.Status.CREATED && !target.awaitingPayment()) {
            throw new PaymentExceptions.PaymentClosed();
        }
        return new Prepared(payment, target.number());
    }

    /** Provider call + tx 2. Also the retry path for a creation whose outcome was unknown (same key). */
    private Payment createAtProvider(StoreId storeId, Prepared prepared) {
        Payment payment = prepared.payment();
        String key = "payment:" + payment.orderId();
        tenant.runInStore(storeId, () -> records.beginOperation(storeId, payment.id(), "CREATE", key, payment.amountMinor(), clock.instant()));
        ProviderPayment created;
        try {
            created = gateway.createPayment(new CreatePayment(storeId.value(), payment.id(), payment.orderId(), prepared.orderNumber(),
                    payment.amountMinor(), payment.currency()), key);
        } catch (ProviderUnavailableException e) {
            finishOperation(storeId, key, e);
            log.atWarn().addKeyValue("paymentId", payment.id()).addKeyValue("outcomeUnknown", e.outcomeUnknown())
                    .log("Payment creation at the provider did not complete; safe to retry with the same key: " + e.getMessage());
            throw new PaymentExceptions.ProviderUnavailable();
        } catch (ProviderRejectedException e) {
            tenant.runInStore(storeId, () -> {
                records.finishOperation(storeId, key, "FAILED", null, e.code(), clock.instant());
                payments.lock(storeId.value(), payment.id()).ifPresent(locked -> locked.fail(e.code(), clock.instant()));
            });
            throw new PaymentExceptions.PaymentRejected(e.code());
        }
        return tenant.inStore(storeId, () -> {
            records.finishOperation(storeId, key, "SUCCEEDED", created.id(), null, clock.instant());
            Payment locked = payments.lock(storeId.value(), payment.id()).orElseThrow();
            locked.attach(created.id(), created.clientSecret(), clock.instant());
            applyProviderState(storeId, locked, created.status());
            return locked;
        });
    }

    /** A verified provider event (from the job queue; at-least-once, so everything here is idempotent). */
    public void apply(ProviderEvent event) {
        if (event.type() == EventType.IGNORED) {
            return;
        }
        if (event.storeId() == null) {
            unmatched(event);
            return;
        }
        StoreId storeId = StoreId.of(event.storeId());
        if (event.type() == EventType.REFUND_UPDATED) {
            if (!refundService.applyProviderUpdate(storeId, event)) {
                unmatched(event);
            }
            return;
        }
        tenant.runInStore(storeId, () -> {
            Optional<Payment> found = event.paymentId() == null ? Optional.empty() : payments.lock(storeId.value(), event.paymentId());
            if (found.isEmpty() && event.providerPaymentId() != null) {
                found = payments.lockByProviderPaymentId(storeId.value(), gateway.providerName(), event.providerPaymentId());
            }
            if (found.isEmpty()) {
                unmatched(event);
                return;
            }
            Payment payment = found.get();
            Instant now = clock.instant();
            if (event.providerPaymentId() != null) {
                payment.attach(event.providerPaymentId(), null, now);
            }
            switch (event.type()) {
                case PAYMENT_SUCCEEDED -> captured(storeId, payment);
                case PAYMENT_FAILED -> {
                    String code = event.failureCode() == null ? "unknown" : event.failureCode();
                    if (payment.recordFailure(code, now)) {
                        orders.recordPaymentFailure(storeId, payment.orderId(), code);
                        meterRegistry.counter("storeforge.payments.declined", "provider", payment.provider()).increment();
                    }
                }
                case PAYMENT_CANCELED -> payment.cancel(now);
                default -> {
                }
            }
        });
    }

    /**
     * OrderCancelled (merchant, or the payment window expired): cancel the payment at the provider so
     * the customer can no longer pay. If the provider says it already succeeded, the customer paid
     * first: apply the capture, which refunds automatically because the order can't take it anymore.
     */
    public void cancelForOrder(StoreId storeId, UUID orderId) {
        Optional<Payment> found = tenant.inStore(storeId, () -> payments.findByStoreIdAndOrderId(storeId.value(), orderId));
        if (found.isEmpty() || !found.get().open()) {
            return;
        }
        Payment payment = found.get();
        if (payment.providerPaymentId() == null) {
            // Never created at the provider (or outcome unknown): no client secret was handed out, nobody can pay it.
            tenant.runInStore(storeId, () -> payments.lock(storeId.value(), payment.id()).ifPresent(p -> p.cancel(clock.instant())));
            return;
        }
        String key = "cancel:" + payment.id();
        tenant.runInStore(storeId, () -> records.beginOperation(storeId, payment.id(), "CANCEL", key, null, clock.instant()));
        ProviderPayment state;
        try {
            try {
                state = gateway.cancelPayment(payment.providerPaymentId(), key);
            } catch (ProviderRejectedException e) {
                state = gateway.retrievePayment(payment.providerPaymentId());   // typically: already succeeded
            }
        } catch (ProviderUnavailableException e) {
            finishOperation(storeId, key, e);
            throw e;   // the job retries with backoff
        } catch (ProviderRejectedException e) {
            // The provider doesn't know this payment at all (another account/environment): nobody can pay
            // it, so cancel it here instead of retrying forever (found in EXP-012).
            tenant.runInStore(storeId, () -> {
                records.finishOperation(storeId, key, "FAILED", null, e.code(), clock.instant());
                payments.lock(storeId.value(), payment.id()).ifPresent(locked -> locked.cancel(clock.instant()));
            });
            log.atWarn().addKeyValue("paymentId", payment.id()).log("Provider does not know this payment; cancelled it locally: " + e.code());
            return;
        }
        ProviderPayment result = state;
        tenant.runInStore(storeId, () -> {
            boolean cancelled = result.status() == PaymentStatus.CANCELED;
            records.finishOperation(storeId, key, cancelled ? "SUCCEEDED" : "FAILED", result.id(),
                    cancelled ? null : "provider_status_" + result.status().name().toLowerCase(), clock.instant());
            payments.lock(storeId.value(), payment.id()).ifPresent(locked -> applyProviderState(storeId, locked, result.status()));
        });
    }

    /**
     * Safety net for lost webhooks and unknown outcomes: ask the provider for the truth about a
     * payment that has not reached a final state in a while.
     */
    public void reconcile(StoreId storeId, UUID paymentId) {
        Optional<Prepared> stale = tenant.inStore(storeId, () -> {
            Optional<Payment> found = payments.lock(storeId.value(), paymentId).filter(Payment::open);
            if (found.isEmpty()) {
                return Optional.<Prepared>empty();
            }
            Payment payment = found.get();
            Orders.PaymentTarget target = orders.paymentTarget(storeId, payment.orderId());
            if (payment.providerPaymentId() == null && !target.awaitingPayment()) {
                payment.cancel(clock.instant());
                return Optional.<Prepared>empty();
            }
            payment.touch(clock.instant());
            return Optional.of(new Prepared(payment, target.number()));
        });
        if (stale.isEmpty()) {
            return;
        }
        Payment payment = stale.get().payment();
        if (payment.providerPaymentId() == null) {
            createAtProvider(storeId, stale.get());   // unknown creation: retry with the same key
            return;
        }
        ProviderPayment state = gateway.retrievePayment(payment.providerPaymentId());
        tenant.runInStore(storeId, () -> payments.lock(storeId.value(), paymentId).ifPresent(locked -> {
            if (applyProviderState(storeId, locked, state.status())) {
                log.atWarn().addKeyValue("paymentId", paymentId)
                        .log("Reconciliation found a captured payment whose webhook never arrived; applied it");
                meterRegistry.counter("storeforge.payments.reconciled", "provider", locked.provider()).increment();
            }
        }));
    }

    /** @return true if this call captured the payment */
    private boolean applyProviderState(StoreId storeId, Payment locked, PaymentStatus status) {
        return switch (status) {
            case SUCCEEDED -> captured(storeId, locked);
            case CANCELED -> {
                locked.cancel(clock.instant());
                yield false;
            }
            default -> false;
        };
    }

    /**
     * The money arrived. Payment and order change in ONE transaction (payment row locked first,
     * then the order row): they can't disagree. If the order can no longer take the money
     * (cancelled/expired meanwhile, or already paid another way), the money goes back
     * automatically; the refund row is created in this same transaction, so it can't be forgotten.
     */
    private boolean captured(StoreId storeId, Payment locked) {
        if (!locked.capture(clock.instant())) {
            return false;   // duplicate or late report
        }
        payments.flush();
        meterRegistry.counter("storeforge.payments.captured", "provider", locked.provider()).increment();
        if (orders.applyPayment(storeId, locked.orderId(), locked.id(), "card") == Orders.PaymentOutcome.NOT_PAYABLE) {
            refundService.compensate(storeId, locked);
            meterRegistry.counter("storeforge.payments.compensations").increment();
            log.atWarn().addKeyValue("paymentId", locked.id()).addKeyValue("orderId", locked.orderId())
                    .log("Payment captured for an order that can no longer be paid; refunding it automatically");
        }
        return true;
    }

    private void unmatched(ProviderEvent event) {
        meterRegistry.counter("storeforge.payments.events.unmatched").increment();
        log.atWarn().addKeyValue("providerEventId", event.id()).addKeyValue("eventType", event.type().name())
                .log("Provider event does not match any payment or refund; ignored");
    }

    private void finishOperation(StoreId storeId, String key, ProviderUnavailableException e) {
        tenant.runInStore(storeId, () -> records.finishOperation(storeId, key, e.outcomeUnknown() ? "UNKNOWN" : "FAILED", null,
                e.outcomeUnknown() ? "outcome_unknown" : "provider_unavailable", clock.instant()));
    }

    static PaymentSession session(Payment payment) {
        return new PaymentSession(payment.id(), payment.provider(), payment.status().name(), payment.amountMinor(), payment.currency(),
                payment.status() == Payment.Status.PENDING ? payment.clientSecret() : null, payment.failureCode());
    }
}
