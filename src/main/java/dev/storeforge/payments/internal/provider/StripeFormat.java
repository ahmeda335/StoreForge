package dev.storeforge.payments.internal.provider;

import dev.storeforge.payments.internal.provider.PaymentProvider.EventType;
import dev.storeforge.payments.internal.provider.PaymentProvider.PaymentStatus;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderEvent;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderPayment;
import dev.storeforge.payments.internal.provider.PaymentProvider.ProviderRefund;
import dev.storeforge.payments.internal.provider.PaymentProvider.RefundStatus;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Stripe's wire format: webhook signatures and the JSON of PaymentIntents, Refunds and Events.
 * The fake provider speaks the same format, so the verification and parsing code that production
 * relies on is exercised by every test.
 */
public final class StripeFormat {

    public static final String SIGNATURE_HEADER = "Stripe-Signature";

    private StripeFormat() {
    }

    /** {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">} */
    public static String sign(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
    }

    /**
     * Checks the signature over the exact raw bytes received (parsing first and re-serializing would
     * change them), in constant time, and rejects timestamps outside the tolerance: a captured,
     * validly signed request replayed later is refused.
     */
    public static void verify(String body, @Nullable String header, String secret, Duration tolerance, Instant now) {
        if (header == null || header.isBlank()) {
            throw new InvalidWebhookException("missing signature header");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    throw new InvalidWebhookException("malformed timestamp");
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidWebhookException("malformed signature header");
        }
        byte[] expected = hmac(secret, timestamp + "." + body).getBytes(StandardCharsets.US_ASCII);
        boolean match = signatures.stream()
                .anyMatch(candidate -> MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII)));
        if (!match) {
            throw new InvalidWebhookException("signature mismatch");
        }
        if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(tolerance) > 0) {
            throw new InvalidWebhookException("timestamp outside tolerance");
        }
    }

    public static ProviderEvent parseEvent(JsonNode event) {
        String id = text(event, "id");
        String type = text(event, "type");
        JsonNode object = event.path("data").path("object");
        if (id == null || type == null || object.isMissingNode()) {
            throw new InvalidWebhookException("not an event");
        }
        JsonNode metadata = object.path("metadata");
        EventType eventType = switch (type) {
            case "payment_intent.succeeded" -> EventType.PAYMENT_SUCCEEDED;
            case "payment_intent.payment_failed" -> EventType.PAYMENT_FAILED;
            case "payment_intent.canceled" -> EventType.PAYMENT_CANCELED;
            case "refund.created", "refund.updated", "refund.failed" -> EventType.REFUND_UPDATED;
            default -> EventType.IGNORED;
        };
        boolean refund = eventType == EventType.REFUND_UPDATED;
        return new ProviderEvent(id, eventType, uuid(metadata, "store_id"), uuid(metadata, "payment_id"),
                refund ? text(object, "payment_intent") : text(object, "id"),
                uuid(metadata, "refund_id"), refund ? text(object, "id") : null,
                refund ? refundStatus(text(object, "status")) : null,
                refund ? null : failureCode(object));
    }

    public static ProviderPayment payment(JsonNode intent) {
        return new ProviderPayment(text(intent, "id"), text(intent, "client_secret"), paymentStatus(text(intent, "status")),
                intent.path("amount").asLong(), failureCode(intent));
    }

    public static ProviderRefund refund(JsonNode refund) {
        return new ProviderRefund(text(refund, "id"), refundStatus(text(refund, "status")));
    }

    static PaymentStatus paymentStatus(@Nullable String status) {
        return switch (status == null ? "" : status) {
            case "succeeded" -> PaymentStatus.SUCCEEDED;
            case "canceled" -> PaymentStatus.CANCELED;
            case "processing", "requires_capture" -> PaymentStatus.PROCESSING;
            default -> PaymentStatus.REQUIRES_ACTION;   // requires_payment_method / _confirmation / _action
        };
    }

    static RefundStatus refundStatus(@Nullable String status) {
        return switch (status == null ? "" : status) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> RefundStatus.PENDING;   // pending, requires_action
        };
    }

    private static @Nullable String failureCode(JsonNode intent) {
        JsonNode error = intent.path("last_payment_error");
        String decline = text(error, "decline_code");
        return decline != null ? decline : text(error, "code");
    }

    static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isEmpty() ? value.asString() : null;
    }

    private static @Nullable UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
