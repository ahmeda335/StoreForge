package dev.storeforge.webhooks.internal.delivery;

import dev.storeforge.identity.UserDirectory;
import dev.storeforge.jobs.Jobs;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.Notifications;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.webhooks.internal.WebhookExceptions;
import dev.storeforge.webhooks.internal.WebhooksProperties;
import dev.storeforge.webhooks.internal.delivery.DeliveryStore.Attempt;
import dev.storeforge.webhooks.internal.delivery.DeliveryStore.Delivery;
import dev.storeforge.webhooks.internal.security.SecretBox;
import dev.storeforge.webhooks.internal.security.WebhookSignature;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionStore;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionStore.Subscription;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * One delivery attempt, and the merchant's view and replay of deliveries.
 *
 * <p>Retries are scheduled HERE, by the webhook retry policy (1m … 24h over ~3 days), as a new
 * delayed job per attempt. The job queue's own retry/backoff only covers infrastructure failures
 * (database down) of this code: two different policies for two different failures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeliveryService {

    public static final String JOB = "webhooks.deliver";

    private final DeliveryStore deliveries;
    private final SubscriptionStore subscriptions;
    private final WebhookSender sender;
    private final RetryPolicy retryPolicy;
    private final SecretBox secretBox;
    private final Jobs jobs;
    private final Notifications notifications;
    private final UserDirectory users;
    private final StoreAccess access;
    private final TenantTransactions tenant;
    private final WebhooksProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public record DeliveryDetails(Delivery delivery, List<Attempt> attempts) {
    }

    record Prepared(Delivery delivery, URI url, List<String> secrets) {
    }

    /** Dedup key of the job for one attempt: (delivery, replay cycle, attempt in that cycle). */
    static String jobKey(UUID deliveryId, int replay, int attempt) {
        return deliveryId + ":" + replay + ":" + attempt;
    }

    public void attempt(StoreId storeId, UUID deliveryId) {
        Prepared prepared = tenant.inStore(storeId, () -> prepare(storeId, deliveryId));
        if (prepared == null) {
            return;
        }
        Delivery delivery = prepared.delivery();
        Instant requestedAt = clock.instant();
        long timestamp = requestedAt.getEpochSecond();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-StoreForge-Topic", delivery.topic());
        headers.put("X-StoreForge-Event-Id", delivery.eventId().toString());
        headers.put("X-StoreForge-Delivery-Id", delivery.id().toString());
        headers.put("X-StoreForge-Store-Id", storeId.toString());
        headers.put("X-StoreForge-Api-Version", properties.apiVersion());
        headers.put("X-StoreForge-Timestamp", Long.toString(timestamp));
        headers.put("X-StoreForge-Signature", WebhookSignature.header(prepared.secrets(), timestamp, delivery.payload()));

        // The network call: outside any transaction (no locks or pooled connection held for up to 10 s).
        WebhookSender.Response response = sender.send(prepared.url(), headers, delivery.payload());

        String outcome = tenant.inStore(storeId, () -> record(storeId, delivery, requestedAt, response));
        Timer.builder("storeforge.webhooks.delivery.duration").tag("outcome", outcome).register(meterRegistry)
                .record(response.durationMs(), TimeUnit.MILLISECONDS);
        meterRegistry.counter("storeforge.webhooks.deliveries", "outcome", outcome).increment();
        if (!response.succeeded()) {
            log.atWarn().addKeyValue("deliveryId", delivery.id()).addKeyValue("host", prepared.url().getHost())
                    .addKeyValue("httpStatus", response.statusCode()).addKeyValue("outcome", outcome)
                    .log("Webhook delivery attempt failed: " + (response.error() == null ? "HTTP " + response.statusCode() : response.error()));
        }
    }

    private @Nullable Prepared prepare(StoreId storeId, UUID deliveryId) {
        Delivery delivery = deliveries.find(storeId, deliveryId, false).orElse(null);
        if (delivery == null || !("PENDING".equals(delivery.status()) || "RETRYING".equals(delivery.status()))) {
            return null;   // finished, replayed meanwhile, or purged: nothing to do (idempotent job)
        }
        Subscription subscription = subscriptions.find(storeId, delivery.subscriptionId(), false).orElse(null);
        if (subscription == null || "DISABLED".equals(subscription.status())) {
            deliveries.park(storeId, deliveryId, "DISCARDED", clock.instant());
            return null;
        }
        if ("PAUSED_FAILING".equals(subscription.status())) {
            deliveries.park(storeId, deliveryId, "PAUSED", clock.instant());
            return null;
        }
        List<String> secrets = new ArrayList<>();
        secrets.add(secretBox.open(subscription.secretCiphertext(), subscription.id().toString()));
        if (subscription.previousSecretCiphertext() != null && subscription.previousSecretExpiresAt().isAfter(clock.instant())) {
            secrets.add(secretBox.open(subscription.previousSecretCiphertext(), subscription.id().toString()));
        }
        return new Prepared(delivery, URI.create(subscription.url()), secrets);
    }

    /** @return the outcome label: succeeded | retry | dead | blocked | paused */
    private String record(StoreId storeId, Delivery delivery, Instant requestedAt, WebhookSender.Response response) {
        Instant now = clock.instant();
        deliveries.insertAttempt(storeId, delivery.id(), requestedAt, response.durationMs(), response.statusCode(), response.succeeded(),
                response.error(), response.excerpt());
        if (response.succeeded()) {
            deliveries.recordAttempt(storeId, delivery.id(), "SUCCEEDED", null, response.statusCode(), null, now);
            subscriptions.recordSuccess(storeId, delivery.subscriptionId(), now);
            return "succeeded";
        }
        String error = response.error() == null ? "HTTP " + response.statusCode() : response.error();
        int failures = subscriptions.recordFailure(storeId, delivery.subscriptionId(), now);
        if (failures >= properties.pauseAfterConsecutiveFailures() && subscriptions.pause(storeId, delivery.subscriptionId(), now)) {
            notifyPaused(storeId, delivery.subscriptionId(), failures);
        }
        if (response.blocked()) {
            // The destination resolves to a forbidden address now: retrying would only knock again.
            deliveries.recordAttempt(storeId, delivery.id(), "DEAD", null, null, error, now);
            return "blocked";
        }
        int attempts = delivery.attempts() + 1;
        RetryPolicy.Decision decision = retryPolicy.afterFailure(attempts, response.statusCode(), response.retryAfter(),
                ThreadLocalRandom.current());
        if (!decision.retry()) {
            deliveries.recordAttempt(storeId, delivery.id(), "DEAD", null, response.statusCode(), error, now);
            return "dead";
        }
        Instant next = now.plus(decision.delay());
        deliveries.recordAttempt(storeId, delivery.id(), "RETRYING", next, response.statusCode(), error, now);
        jobs.enqueue(storeId, JOB, jobKey(delivery.id(), delivery.replays(), attempts + 1), Map.of("deliveryId", delivery.id().toString()), next);
        return "retry";
    }

    private void notifyPaused(StoreId storeId, UUID subscriptionId, int failures) {
        meterRegistry.counter("storeforge.webhooks.subscriptions.paused").increment();
        Subscription subscription = subscriptions.find(storeId, subscriptionId, false).orElseThrow();
        log.atWarn().addKeyValue("subscriptionId", subscriptionId).addKeyValue("consecutiveFailures", failures)
                .log("Webhook subscription paused after consecutive failures");
        if (subscription.createdBy() == null) {
            return;
        }
        users.find(subscription.createdBy()).ifPresent(user -> notifications.enqueueEmail(storeId, new EmailMessage(user.email(),
                        "Webhook endpoint paused: " + URI.create(subscription.url()).getHost(),
                        "We paused deliveries to " + subscription.url() + " after " + failures + " consecutive failed attempts.\n\n"
                                + "New events are kept and can be replayed. Fix the endpoint, re-activate the subscription, then replay "
                                + "the paused and failed deliveries from the delivery history."),
                "webhook-paused:" + subscriptionId + ":" + subscription.pausedAt().getEpochSecond(), false));
    }

    // ---- merchant API ---------------------------------------------------------------------------

    public Page<Delivery> list(UUID userId, StoreId storeId, UUID subscriptionId, @Nullable String status, int limit, @Nullable Cursor after) {
        return tenant.inStore(storeId, () -> {
            requireSubscription(userId, storeId, subscriptionId);
            return Page.of(deliveries.page(storeId, subscriptionId, status, limit + 1, after), limit,
                    d -> new Cursor(d.createdAt(), d.id()));
        });
    }

    public DeliveryDetails get(UUID userId, StoreId storeId, UUID subscriptionId, UUID deliveryId) {
        return tenant.inStore(storeId, () -> {
            requireSubscription(userId, storeId, subscriptionId);
            Delivery delivery = deliveries.find(storeId, deliveryId, false).filter(d -> d.subscriptionId().equals(subscriptionId))
                    .orElseThrow(WebhookExceptions.DeliveryNotFound::new);
            return new DeliveryDetails(delivery, deliveries.attempts(storeId, deliveryId));
        });
    }

    /** Same event id, new attempts: receivers that already processed it recognise it as a duplicate. */
    public void replay(UUID userId, StoreId storeId, UUID subscriptionId, UUID deliveryId) {
        tenant.runInStore(storeId, () -> {
            requireSubscription(userId, storeId, subscriptionId);
            deliveries.find(storeId, deliveryId, false).filter(d -> d.subscriptionId().equals(subscriptionId))
                    .orElseThrow(WebhookExceptions.DeliveryNotFound::new);
            if (!startReplay(storeId, deliveryId)) {
                throw new WebhookExceptions.DeliveryInProgress();
            }
        });
    }

    /** "Replay everything that failed since 10:00". @return how many deliveries were queued */
    public int replayAll(UUID userId, StoreId storeId, UUID subscriptionId, Collection<String> statuses, Instant since) {
        return tenant.inStore(storeId, () -> {
            requireSubscription(userId, storeId, subscriptionId);
            int queued = 0;
            for (UUID id : deliveries.replayable(storeId, subscriptionId, statuses, since, 1000)) {
                queued += startReplay(storeId, id) ? 1 : 0;
            }
            return queued;
        });
    }

    private boolean startReplay(StoreId storeId, UUID deliveryId) {
        return deliveries.replay(storeId, deliveryId, clock.instant())
                .map(replay -> {
                    jobs.enqueue(storeId, JOB, jobKey(deliveryId, replay, 1), Map.of("deliveryId", deliveryId.toString()));
                    return true;
                })
                .orElse(false);
    }

    private void requireSubscription(UUID userId, StoreId storeId, UUID subscriptionId) {
        access.require(userId, storeId, Permission.WEBHOOKS_MANAGE);
        subscriptions.find(storeId, subscriptionId, false).orElseThrow(WebhookExceptions.SubscriptionNotFound::new);
    }
}
