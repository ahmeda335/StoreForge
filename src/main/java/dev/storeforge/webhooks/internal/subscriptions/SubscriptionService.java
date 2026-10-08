package dev.storeforge.webhooks.internal.subscriptions;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.developer.ApiClientPrincipal;
import dev.storeforge.developer.ApiCredentialRevoked;
import dev.storeforge.developer.Scope;
import dev.storeforge.stores.Actor;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.webhooks.internal.WebhookExceptions;
import dev.storeforge.webhooks.internal.WebhooksProperties;
import dev.storeforge.webhooks.internal.delivery.Topics;
import dev.storeforge.webhooks.internal.security.DestinationNotAllowedException;
import dev.storeforge.webhooks.internal.security.EndpointPolicy;
import dev.storeforge.webhooks.internal.security.SecretBox;
import dev.storeforge.webhooks.internal.security.WebhookSignature;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionStore.Subscription;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Merchant management of webhook subscriptions ({@code webhooks:manage}). */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionStore subscriptions;
    private final EndpointPolicy endpointPolicy;
    private final SecretBox secretBox;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final WebhooksProperties properties;
    private final Clock clock;

    /** The secret is returned exactly once, at creation (and at rotation). It is never readable again. */
    public record WithSecret(Subscription subscription, String secret) {
    }

    public WithSecret create(UUID userId, StoreId storeId, String url, List<String> topics, @Nullable String apiVersion) {
        requireManage(userId, storeId);
        return create(Actor.user(userId), storeId, url, topics, apiVersion, null);
    }

    /**
     * An app's own subscription (developer API): owned by its credential, removed when the credential
     * is revoked, and limited to topics the credential's scopes can read.
     */
    public WithSecret createForClient(ApiClientPrincipal client, String url, List<String> topics) {
        for (String topic : validateTopics(topics)) {
            Scope needed = Topics.requiredScope(topic);
            if (!client.has(needed)) {
                throw new WebhookExceptions.TopicNotAllowed(topic, needed.code());
            }
        }
        return create(client.actor(), client.storeId(), url, topics, null, client.credentialId());
    }

    public List<Subscription> listForClient(ApiClientPrincipal client) {
        return tenant.inStore(client.storeId(), () -> subscriptions.ownedBy(client.storeId(), client.credentialId()));
    }

    public void deleteForClient(ApiClientPrincipal client, UUID id) {
        tenant.runInStore(client.storeId(), () -> {
            Subscription owned = subscriptions.find(client.storeId(), id, true)
                    .filter(s -> client.credentialId().equals(s.ownerCredentialId()))
                    .orElseThrow(WebhookExceptions.SubscriptionNotFound::new);
            subscriptions.delete(client.storeId(), owned.id());
            audit.record(client.storeId(), client.actor().auditType(), client.credentialId(), "webhook.subscription_deleted",
                    "webhook_subscription", id.toString(), Map.of());
        });
    }

    /** Revocation and cleanup commit together: this runs inside the revoking transaction. */
    @EventListener
    public void onCredentialRevoked(ApiCredentialRevoked revoked) {
        subscriptions.deleteOwnedBy(revoked.storeId(), revoked.credentialId());
    }

    private WithSecret create(Actor actor, StoreId storeId, String url, List<String> topics, @Nullable String apiVersion,
                              @Nullable UUID ownerCredentialId) {
        // Outside any transaction: this resolves DNS, which can take a while.
        URI checked = validateUrl(url);
        List<String> checkedTopics = validateTopics(topics);
        String version = validateVersion(apiVersion);
        return tenant.inStore(storeId, () -> {
            if (actor instanceof Actor.User) {
                access.require(actor, storeId, Permission.WEBHOOKS_MANAGE);
            }
            subscriptions.lockStore(storeId);
            if (subscriptions.count(storeId) >= properties.maxSubscriptionsPerStore()) {
                throw new WebhookExceptions.LimitReached(properties.maxSubscriptionsPerStore());
            }
            UUID id = Ids.newId();
            String secret = WebhookSignature.newSecret();
            subscriptions.insert(storeId, id, checked.toString(), checkedTopics, version, secretBox.seal(secret, id.toString()),
                    actor instanceof Actor.User ? actor.id() : null, ownerCredentialId, clock.instant());
            audit.record(storeId, actor.auditType(), actor.id(), "webhook.subscription_created", "webhook_subscription", id.toString(),
                    Map.of("host", checked.getHost(), "topics", checkedTopics));
            return new WithSecret(subscriptions.find(storeId, id, false).orElseThrow(), secret);
        });
    }

    public List<Subscription> list(UUID userId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.WEBHOOKS_MANAGE);
            return subscriptions.list(storeId);
        });
    }

    public Subscription get(UUID userId, StoreId storeId, UUID id) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.WEBHOOKS_MANAGE);
            return subscriptions.find(storeId, id, false).orElseThrow(WebhookExceptions.SubscriptionNotFound::new);
        });
    }

    /**
     * Changes url/topics, disables, or re-activates. Re-activating a PAUSED_FAILING subscription
     * resets its failure counter; deliveries parked while paused are NOT sent automatically: the
     * merchant replays them (bulk replay) once the receiver is fixed.
     */
    public Subscription update(UUID userId, StoreId storeId, UUID id, @Nullable String url, @Nullable List<String> topics,
                               @Nullable String status) {
        requireManage(userId, storeId);
        URI checked = url == null ? null : validateUrl(url);
        List<String> checkedTopics = topics == null ? null : validateTopics(topics);
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.WEBHOOKS_MANAGE);
            Subscription current = subscriptions.find(storeId, id, true).orElseThrow(WebhookExceptions.SubscriptionNotFound::new);
            String newStatus = status == null ? current.status() : status;
            boolean reactivated = "ACTIVE".equals(newStatus) && !"ACTIVE".equals(current.status());
            subscriptions.update(storeId, id, checked == null ? current.url() : checked.toString(),
                    checkedTopics == null ? current.topics() : checkedTopics, newStatus, reactivated, clock.instant());
            audit.recordUserAction(storeId, userId, "webhook.subscription_updated", "webhook_subscription", id.toString(),
                    Map.of("status", newStatus, "urlChanged", checked != null, "topicsChanged", checkedTopics != null));
            return subscriptions.find(storeId, id, false).orElseThrow();
        });
    }

    public void delete(UUID userId, StoreId storeId, UUID id) {
        tenant.runInStore(storeId, () -> {
            access.require(userId, storeId, Permission.WEBHOOKS_MANAGE);
            if (!subscriptions.delete(storeId, id)) {
                throw new WebhookExceptions.SubscriptionNotFound();
            }
            audit.recordUserAction(storeId, userId, "webhook.subscription_deleted", "webhook_subscription", id.toString(), Map.of());
        });
    }

    /**
     * New secret now; the old one keeps signing (as a second {@code v1=}) for the overlap period, so
     * the receiver can switch its configuration without dropping a single delivery.
     */
    public WithSecret rotateSecret(UUID userId, StoreId storeId, UUID id) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.WEBHOOKS_MANAGE);
            Subscription current = subscriptions.find(storeId, id, true).orElseThrow(WebhookExceptions.SubscriptionNotFound::new);
            String secret = WebhookSignature.newSecret();
            Instant now = clock.instant();
            subscriptions.rotate(storeId, id, secretBox.seal(secret, id.toString()), current.secretCiphertext(),
                    now.plus(properties.secretOverlap()), now);
            audit.recordUserAction(storeId, userId, "webhook.secret_rotated", "webhook_subscription", id.toString(), Map.of());
            return new WithSecret(subscriptions.find(storeId, id, false).orElseThrow(), secret);
        });
    }

    private void requireManage(UUID userId, StoreId storeId) {
        // Permission first: a non-member must get 404, not a URL validation error about a store they can't see.
        tenant.runInStore(storeId, () -> access.require(userId, storeId, Permission.WEBHOOKS_MANAGE));
    }

    private URI validateUrl(String url) {
        try {
            return endpointPolicy.validate(url.trim());
        } catch (DestinationNotAllowedException e) {
            throw new WebhookExceptions.UrlNotAllowed(e.getMessage());
        }
    }

    private static List<String> validateTopics(List<String> topics) {
        for (String topic : topics) {
            if (!Topics.ALL.contains(topic)) {
                throw new WebhookExceptions.UnknownTopic(topic);
            }
        }
        return topics.stream().distinct().sorted().toList();
    }

    private String validateVersion(@Nullable String apiVersion) {
        if (apiVersion != null && !apiVersion.equals(properties.apiVersion())) {
            throw new WebhookExceptions.UnsupportedApiVersion(properties.apiVersion());
        }
        return properties.apiVersion();
    }
}
