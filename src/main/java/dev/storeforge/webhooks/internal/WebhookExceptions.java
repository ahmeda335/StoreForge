package dev.storeforge.webhooks.internal;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

public final class WebhookExceptions {

    private WebhookExceptions() {
    }

    public static class SubscriptionNotFound extends StoreForgeException {
        public SubscriptionNotFound() {
            super(HttpStatus.NOT_FOUND, "webhook_subscription_not_found", "Webhook subscription not found.");
        }
    }

    public static class DeliveryNotFound extends StoreForgeException {
        public DeliveryNotFound() {
            super(HttpStatus.NOT_FOUND, "webhook_delivery_not_found", "Webhook delivery not found.");
        }
    }

    /** The reason is safe to show: it describes the URL the merchant typed, nothing about our network. */
    public static class UrlNotAllowed extends StoreForgeException {
        public UrlNotAllowed(String reason) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "webhook_url_not_allowed", "The webhook URL is not allowed: " + reason + ".");
        }
    }

    public static class UnknownTopic extends StoreForgeException {
        public UnknownTopic(String topic) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_topic", "Unknown webhook topic: " + topic + ".");
        }
    }

    public static class TopicNotAllowed extends StoreForgeException {
        public TopicNotAllowed(String topic, String scope) {
            super(HttpStatus.FORBIDDEN, "insufficient_scope", "Subscribing to " + topic + " requires the " + scope + " scope.");
        }
    }

    public static class UnsupportedApiVersion extends StoreForgeException {
        public UnsupportedApiVersion(String supported) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_api_version", "Supported webhook API version: " + supported + ".");
        }
    }

    public static class LimitReached extends StoreForgeException {
        public LimitReached(int limit) {
            super(HttpStatus.CONFLICT, "webhook_subscription_limit", "A store can have at most " + limit + " webhook subscriptions.");
        }
    }

    public static class DeliveryInProgress extends StoreForgeException {
        public DeliveryInProgress() {
            super(HttpStatus.CONFLICT, "webhook_delivery_in_progress", "This delivery is still being attempted; replay it once it has finished.");
        }
    }
}
