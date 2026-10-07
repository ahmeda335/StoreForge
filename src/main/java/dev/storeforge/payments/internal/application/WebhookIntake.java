package dev.storeforge.payments.internal.application;

import dev.storeforge.jobs.Jobs;
import dev.storeforge.payments.internal.domain.PaymentExceptions;
import dev.storeforge.payments.internal.persistence.PaymentRecords;
import dev.storeforge.payments.internal.provider.InvalidWebhookException;
import dev.storeforge.payments.internal.provider.PaymentGateway;
import dev.storeforge.payments.internal.provider.PaymentProvider.EventType;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderEvent;
import dev.storeforge.shared.tenancy.StoreId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbound provider webhooks: verify → store (deduplicated by event id) + enqueue processing → 200.
 * Nothing slow happens before the answer, so the provider never times out and retries because of
 * us; duplicates (providers retry, sometimes concurrently) hit the primary key and are no-ops.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebhookIntake {

    public static final String JOB = "payments.provider-event";

    private final PaymentGateway gateway;
    private final PaymentRecords records;
    private final Jobs jobs;
    private final TransactionTemplate transaction;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public void receive(String provider, String rawBody, @Nullable String signature) {
        if (!gateway.providerName().equals(provider)) {
            throw new PaymentExceptions.UnknownProvider();
        }
        ProviderEvent event;
        try {
            event = gateway.parseWebhook(rawBody, signature);
        } catch (InvalidWebhookException e) {
            count(provider, "rejected");
            log.atWarn().addKeyValue("provider", provider).addKeyValue("reason", e.getMessage()).log("Rejected a payment webhook");
            throw new PaymentExceptions.InvalidWebhook();
        }
        boolean fresh = Boolean.TRUE.equals(transaction.execute(status -> {
            boolean inserted = records.recordProviderEvent(provider, event.id(), event.type().name(), event.storeId(), rawBody,
                    clock.instant());
            if (inserted && event.type() != EventType.IGNORED) {
                jobs.enqueue(event.storeId() == null ? null : StoreId.of(event.storeId()), JOB, provider + ":" + event.id(), event);
            }
            return inserted;
        }));
        count(provider, !fresh ? "duplicate" : event.type() == EventType.IGNORED ? "ignored" : "accepted");
    }

    private void count(String provider, String outcome) {
        meterRegistry.counter("storeforge.payments.webhooks", "provider", provider, "outcome", outcome).increment();
    }
}
