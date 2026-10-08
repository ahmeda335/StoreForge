package dev.storeforge.webhooks.internal.delivery;

import dev.storeforge.jobs.JobHandler;
import dev.storeforge.webhooks.internal.WebhooksProperties;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

final class WebhookJobs {

    private WebhookJobs() {
    }

    /** One delivery attempt. Always completes normally unless OUR infrastructure fails (then the queue retries). */
    @Component
    @RequiredArgsConstructor
    static class DeliveryJob implements JobHandler {

        private final DeliveryService deliveries;

        @Override
        public String type() {
            return DeliveryService.JOB;
        }

        @Override
        public void handle(Job job) {
            deliveries.attempt(job.storeId(), UUID.fromString(job.payload().get("deliveryId").asString()));
        }
    }

    /** Delivery history is kept for the retention period (default 30 days), then purged across stores. */
    @Component
    @Slf4j
    @RequiredArgsConstructor
    static class Housekeeping {

        private final DeliveryStore deliveries;
        private final WebhooksProperties properties;
        private final Clock clock;

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
        void run() {
            int purged = deliveries.purge(clock.instant().minus(properties.retention()));
            if (purged > 0) {
                log.atInfo().addKeyValue("purgedDeliveries", purged).log("Purged old webhook deliveries");
            }
        }
    }
}
