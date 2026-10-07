package dev.storeforge.payments.internal.application;

import dev.storeforge.jobs.Event;
import dev.storeforge.jobs.EventHandler;
import dev.storeforge.jobs.JobHandler;
import dev.storeforge.orders.OrderEvents;
import dev.storeforge.payments.internal.domain.Refund;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderEvent;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The payments module's asynchronous work, all on the P9 job queue (retries, DEAD, tracing). */
final class PaymentJobs {

    private PaymentJobs() {
    }

    /** Applies a verified, stored provider webhook. */
    @Component
    @RequiredArgsConstructor
    static class ProviderEventJob implements JobHandler {

        private final PaymentService payments;
        private final JsonMapper jsonMapper;

        @Override
        public String type() {
            return WebhookIntake.JOB;
        }

        @Override
        public void handle(Job job) {
            payments.apply(jsonMapper.treeToValue(job.payload(), ProviderEvent.class));
        }
    }

    /** Drives a refund to a final state; while the provider reports it pending, check again later (backoff). */
    @Component
    @RequiredArgsConstructor
    static class RefundJob implements JobHandler {

        private final RefundService refunds;

        @Override
        public String type() {
            return RefundService.JOB;
        }

        @Override
        public void handle(Job job) {
            UUID refundId = UUID.fromString(job.payload().get("refundId").asString());
            if (refunds.execute(job.storeId(), refundId) == Refund.Status.PENDING) {
                throw new IllegalStateException("Refund " + refundId + " is still pending at the provider; checking again later");
            }
        }
    }

    /** OrderCancelled → cancel the open payment at the provider (or refund it, if the customer paid first). */
    @Component
    @RequiredArgsConstructor
    static class PaymentCancellations implements EventHandler {

        private final PaymentService payments;

        @Override
        public String name() {
            return "payment-cancellations";
        }

        @Override
        public Set<String> eventTypes() {
            return Set.of(OrderEvents.CANCELLED);
        }

        @Override
        public void handle(Event event) {
            payments.cancelForOrder(event.storeId(), UUID.fromString(event.payload().get("orderId").asString()));
        }
    }
}
