package dev.storeforge.payments.internal.application;

import dev.storeforge.payments.internal.PaymentsProperties;
import dev.storeforge.payments.internal.domain.Payment;
import dev.storeforge.payments.internal.domain.PaymentExceptions;
import dev.storeforge.payments.internal.persistence.PaymentRecords;
import dev.storeforge.payments.internal.persistence.PaymentRepository;
import dev.storeforge.payments.internal.persistence.RefundRepository;
import dev.storeforge.payments.internal.provider.ProviderUnavailableException;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Webhooks get lost (provider incident, our outage longer than their retry window, a bug).
 * Periodically, payments and refunds stuck before a final state are checked with the provider
 * directly. Cross-tenant like the other sweepers (ADR-011): store ids via a definer function,
 * then per-store tenant transactions.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentReconciliation {

    private final PaymentRecords records;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final TenantTransactions tenant;
    private final PaymentsProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public record Result(int payments, int refunds, int failures, int deletedProviderEvents) {
    }

    @Scheduled(fixedDelayString = "${storeforge.payments.reconciliation.interval}",
            initialDelayString = "${storeforge.payments.reconciliation.interval}")
    public void run() {
        Result result = reconcile(clock.instant());
        if (result.payments() + result.refunds() + result.failures() > 0) {
            log.atInfo().addKeyValue("payments", result.payments()).addKeyValue("refunds", result.refunds())
                    .addKeyValue("failures", result.failures()).log("Payment reconciliation run");
        }
    }

    /** @param now a parameter so tests move time instead of sleeping */
    public Result reconcile(Instant now) {
        Instant cutoff = now.minus(properties.reconciliation().staleAfter());
        int checkedPayments = 0;
        int checkedRefunds = 0;
        int failures = 0;
        for (UUID store : records.storesWithStalePayments(cutoff, 100)) {
            StoreId storeId = StoreId.of(store);
            List<UUID> paymentIds = tenant.inStore(storeId, () ->
                    payments.stale(store, EnumSet.of(Payment.Status.CREATED, Payment.Status.PENDING), cutoff, Limit.of(100)));
            for (UUID paymentId : paymentIds) {
                if (isolated("paymentId", paymentId, () -> paymentService.reconcile(storeId, paymentId))) {
                    checkedPayments++;
                } else {
                    failures++;
                }
            }
            List<UUID> refundIds = tenant.inStore(storeId, () -> refunds.stalePending(store, cutoff, Limit.of(100)));
            for (UUID refundId : refundIds) {
                if (isolated("refundId", refundId, () -> refundService.execute(storeId, refundId))) {
                    checkedRefunds++;
                } else {
                    failures++;
                }
            }
        }
        int deleted = records.deleteProviderEventsBefore(now.minus(properties.reconciliation().providerEventRetention()));
        return new Result(checkedPayments, checkedRefunds, failures, deleted);
    }

    /**
     * One item's failure must not stop the others: a single payment the provider doesn't know
     * (another environment, a deleted object) would otherwise abort every run, for every store,
     * and lost payments would silently stop being recovered (found in EXP-011).
     */
    private boolean isolated(String idName, UUID id, Runnable work) {
        try {
            work.run();
            return true;
        } catch (ProviderUnavailableException | PaymentExceptions.ProviderUnavailable e) {
            meterRegistry.counter("storeforge.payments.reconciliation.failures", "reason", "provider_unavailable").increment();
            return false;
        } catch (RuntimeException e) {
            meterRegistry.counter("storeforge.payments.reconciliation.failures", "reason", "error").increment();
            log.atWarn().addKeyValue(idName, id).setCause(e).log("Reconciliation of one item failed; continuing with the others");
            return false;
        }
    }
}
