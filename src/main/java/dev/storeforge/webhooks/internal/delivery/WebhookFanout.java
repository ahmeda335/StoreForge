package dev.storeforge.webhooks.internal.delivery;

import dev.storeforge.jobs.Event;
import dev.storeforge.jobs.EventHandler;
import dev.storeforge.jobs.Jobs;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.webhooks.internal.WebhooksProperties;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionStore;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionStore.Subscription;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Internal event → one delivery per interested subscription, plus its first delivery job, in ONE
 * tenant transaction. {@code UNIQUE (subscription_id, event_id)} makes a redelivered event (the
 * job queue is at-least-once) a no-op. The body is built once here and frozen: retries and replays
 * send exactly the same bytes, with the event id receivers deduplicate on.
 */
@Component
@RequiredArgsConstructor
class WebhookFanout implements EventHandler {

    private final SubscriptionStore subscriptions;
    private final DeliveryStore deliveries;
    private final Jobs jobs;
    private final TenantTransactions tenant;
    private final WebhooksProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    public String name() {
        return "webhooks";
    }

    @Override
    public Set<String> eventTypes() {
        return Topics.BY_EVENT_TYPE.keySet();
    }

    @Override
    public void handle(Event event) {
        StoreId storeId = event.storeId();
        String topic = Topics.BY_EVENT_TYPE.get(event.eventType());
        if (storeId == null || topic == null) {
            return;
        }
        tenant.runInStore(storeId, () -> {
            List<Subscription> interested = subscriptions.subscribedTo(storeId, topic);
            if (interested.isEmpty()) {
                return;
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", event.eventId().toString());
            envelope.put("topic", topic);
            envelope.put("apiVersion", properties.apiVersion());
            envelope.put("storeId", storeId.toString());
            envelope.put("createdAt", event.occurredAt().toString());
            envelope.put("data", Topics.data(event.eventType(), event.payload()));
            String body = jsonMapper.writeValueAsString(envelope);
            for (Subscription subscription : interested) {
                boolean active = "ACTIVE".equals(subscription.status());
                deliveries.insert(storeId, subscription.id(), event.eventId(), topic, body, active ? "PENDING" : "PAUSED", clock.instant())
                        .filter(id -> active)
                        .ifPresent(id -> jobs.enqueue(storeId, DeliveryService.JOB, DeliveryService.jobKey(id, 0, 1), Map.of("deliveryId", id.toString())));
            }
        });
    }
}
