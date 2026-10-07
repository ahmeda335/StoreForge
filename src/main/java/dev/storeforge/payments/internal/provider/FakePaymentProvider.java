package dev.storeforge.payments.internal.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-process payment provider that behaves like Stripe where it matters to us: PaymentIntents
 * with client secrets, idempotency keys (a repeated key returns the first result), refunds,
 * signed webhooks in Stripe's format, and test cards. Not a mock: it keeps state, so tests and
 * local development exercise the real flows, and failure experiments need no network.
 *
 * <p>Failure injection: {@link #failNext} makes the next calls fail BEFORE processing (provider
 * unavailable) or AFTER processing (the response is lost: the operation happened, we don't know).
 *
 * <p>State lives in memory: a restart forgets all intents (local development only).
 */
@Slf4j
public class FakePaymentProvider implements PaymentProvider {

    public static final String NAME = "fake";
    public static final String SUCCESS_CARD = "4242424242424242";
    public static final String DECLINED_CARD = "4000000000000002";

    public enum Failure { UNAVAILABLE, RESPONSE_LOST }

    /** A webhook as it goes over the wire: raw JSON and its signature header. */
    public record Webhook(String type, String body, String signature) {
    }

    private static final class Intent {
        String id;
        String clientSecret;
        Map<String, String> metadata;
        long amount;
        String currency;
        String status = "requires_payment_method";
        long refunded;
        @Nullable String failureCode;
    }

    private static final class Refund {
        String id;
        String intentId;
        Map<String, String> metadata;
        long amount;
        String status;
    }

    private final String webhookSecret;
    private final @Nullable URI webhookUrl;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration webhookTolerance;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final Map<String, Intent> intents = new HashMap<>();
    private final Map<String, Refund> refunds = new HashMap<>();
    private final Map<String, String> idempotency = new HashMap<>();   // key -> object id
    private final Map<String, Integer> createCalls = new HashMap<>();   // idempotency key -> calls
    private final Deque<Failure> failures = new ConcurrentLinkedDeque<>();
    private final List<Webhook> webhooks = new CopyOnWriteArrayList<>();
    private volatile boolean refundsPending;
    private int calls;

    public FakePaymentProvider(String webhookSecret, @Nullable URI webhookUrl, JsonMapper jsonMapper, Clock clock,
                               Duration webhookTolerance) {
        this.webhookSecret = webhookSecret;
        this.webhookUrl = webhookUrl;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.webhookTolerance = webhookTolerance;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public synchronized ProviderPayment createPayment(CreatePayment request, String idempotencyKey) {
        Failure failure = before("create_payment");
        createCalls.merge(idempotencyKey, 1, Integer::sum);
        Intent intent = intents.get(idempotency.computeIfAbsent("create:" + idempotencyKey, key -> {
            Intent created = new Intent();
            created.id = "pi_fake_" + UUID.randomUUID().toString().replace("-", "");
            created.clientSecret = created.id + "_secret_" + UUID.randomUUID().toString().substring(0, 8);
            created.amount = request.amountMinor();
            created.currency = request.currency().toLowerCase();
            created.metadata = Map.of("store_id", request.storeId().toString(), "payment_id", request.paymentId().toString(),
                    "order_id", request.orderId().toString());
            intents.put(created.id, created);
            return created.id;
        }));
        after(failure, "create_payment");
        return view(intent);
    }

    @Override
    public synchronized ProviderPayment retrievePayment(String providerPaymentId) {
        after(before("retrieve_payment"), "retrieve_payment");
        return view(intent(providerPaymentId));
    }

    @Override
    public synchronized ProviderPayment cancelPayment(String providerPaymentId, String idempotencyKey) {
        Failure failure = before("cancel_payment");
        Intent intent = intent(providerPaymentId);
        if (intent.status.equals("succeeded")) {
            throw new ProviderRejectedException("payment_intent_unexpected_state", "This PaymentIntent has already succeeded.");
        }
        if (!intent.status.equals("canceled")) {
            intent.status = "canceled";
            emit("payment_intent.canceled", intentJson(intent));
        }
        after(failure, "cancel_payment");
        return view(intent);
    }

    @Override
    public synchronized ProviderRefund refund(String providerPaymentId, long amountMinor, UUID storeId, UUID refundId,
                                              String idempotencyKey) {
        Failure failure = before("refund");
        String existing = idempotency.get("refund:" + idempotencyKey);
        Refund refund;
        if (existing != null) {
            refund = refunds.get(existing);
        } else {
            Intent intent = intent(providerPaymentId);
            if (!intent.status.equals("succeeded")) {
                throw new ProviderRejectedException("charge_not_captured", "This PaymentIntent has not succeeded.");
            }
            if (amountMinor > intent.amount - intent.refunded) {
                throw new ProviderRejectedException("amount_too_large", "Refund amount exceeds the remaining captured amount.");
            }
            refund = new Refund();
            refund.id = "re_fake_" + UUID.randomUUID().toString().replace("-", "");
            refund.intentId = intent.id;
            refund.amount = amountMinor;
            refund.metadata = Map.of("store_id", storeId.toString(), "refund_id", refundId.toString());
            refund.status = refundsPending ? "pending" : "succeeded";
            intent.refunded += amountMinor;
            refunds.put(refund.id, refund);
            idempotency.put("refund:" + idempotencyKey, refund.id);
            emit("refund.created", refundJson(refund));
        }
        after(failure, "refund");
        return new ProviderRefund(refund.id, StripeFormat.refundStatus(refund.status));
    }

    @Override
    public synchronized ProviderRefund retrieveRefund(String providerRefundId) {
        after(before("retrieve_refund"), "retrieve_refund");
        Refund refund = refunds.get(providerRefundId);
        if (refund == null) {
            throw new ProviderRejectedException("resource_missing", "No such refund: " + providerRefundId);
        }
        return new ProviderRefund(refund.id, StripeFormat.refundStatus(refund.status));
    }

    @Override
    public ProviderEvent parseWebhook(String rawBody, @Nullable String signatureHeader) {
        StripeFormat.verify(rawBody, signatureHeader, webhookSecret, webhookTolerance, clock.instant());
        try {
            return StripeFormat.parseEvent(jsonMapper.readTree(rawBody));
        } catch (tools.jackson.core.JacksonException e) {
            throw new InvalidWebhookException("unparseable body");
        }
    }

    // ---- What the customer's browser and the provider's back office do -------------------------

    /** The customer submits the card form. Returns the webhooks this produced (also delivered, if configured). */
    public synchronized List<Webhook> confirm(String providerPaymentId, String card) {
        Intent intent = intent(providerPaymentId);
        int before = webhooks.size();
        if (!intent.status.startsWith("requires_")) {
            throw new ProviderRejectedException("payment_intent_unexpected_state", "Status is " + intent.status);
        }
        if (SUCCESS_CARD.equals(card)) {
            intent.status = "succeeded";
            intent.failureCode = null;
            emit("payment_intent.succeeded", intentJson(intent));
        } else {
            intent.status = "requires_payment_method";
            intent.failureCode = DECLINED_CARD.equals(card) ? "card_declined" : "incorrect_number";
            emit("payment_intent.payment_failed", intentJson(intent));
        }
        return List.copyOf(webhooks.subList(before, webhooks.size()));
    }

    /** Settles a pending refund (banks take days for some methods). */
    public synchronized List<Webhook> settleRefund(String providerRefundId, boolean succeeded) {
        Refund refund = refunds.get(providerRefundId);
        int before = webhooks.size();
        refund.status = succeeded ? "succeeded" : "failed";
        if (!succeeded) {
            intents.get(refund.intentId).refunded -= refund.amount;
        }
        emit(succeeded ? "refund.updated" : "refund.failed", refundJson(refund));
        return List.copyOf(webhooks.subList(before, webhooks.size()));
    }

    public void failNext(Failure... next) {
        failures.addAll(List.of(next));
    }

    public void refundsPending(boolean pending) {
        refundsPending = pending;
    }

    /** Builds a signed event with an arbitrary timestamp (replay experiments). */
    public Webhook sign(Webhook webhook, long timestamp) {
        return new Webhook(webhook.type(), webhook.body(), StripeFormat.sign(webhookSecret, timestamp, webhook.body()));
    }

    public synchronized int createCalls(String idempotencyKey) {
        return createCalls.getOrDefault(idempotencyKey, 0);
    }

    public synchronized int calls() {
        return calls;
    }

    public synchronized long refundedAtProvider(String providerPaymentId) {
        return intent(providerPaymentId).refunded;
    }

    public synchronized String status(String providerPaymentId) {
        return intent(providerPaymentId).status;
    }

    public List<Webhook> webhooks() {
        return List.copyOf(webhooks);
    }

    public synchronized List<String> refundIds(String providerPaymentId) {
        return refunds.values().stream().filter(r -> r.intentId.equals(providerPaymentId)).map(r -> r.id).toList();
    }

    // ---- internals ------------------------------------------------------------------------------

    private @Nullable Failure before(String operation) {
        calls++;
        Failure failure = failures.poll();
        if (failure == Failure.UNAVAILABLE) {
            throw new ProviderUnavailableException(operation, false, "fake provider unavailable", null);
        }
        return failure;
    }

    private static void after(@Nullable Failure failure, String operation) {
        if (failure == Failure.RESPONSE_LOST) {
            throw new ProviderUnavailableException(operation, true, "response lost after processing (simulated read timeout)", null);
        }
    }

    private Intent intent(String id) {
        Intent intent = intents.get(id);
        if (intent == null) {
            throw new ProviderRejectedException("resource_missing", "No such payment_intent: " + id);
        }
        return intent;
    }

    private static ProviderPayment view(Intent intent) {
        return new ProviderPayment(intent.id, intent.clientSecret, StripeFormat.paymentStatus(intent.status), intent.amount,
                intent.failureCode);
    }

    private Map<String, Object> intentJson(Intent intent) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", intent.id);
        json.put("object", "payment_intent");
        json.put("amount", intent.amount);
        json.put("currency", intent.currency);
        json.put("status", intent.status);
        json.put("metadata", intent.metadata);
        if (intent.failureCode != null) {
            json.put("last_payment_error", Map.of("code", "card_declined", "decline_code", intent.failureCode));
        }
        return json;
    }

    private static Map<String, Object> refundJson(Refund refund) {
        return Map.of("id", refund.id, "object", "refund", "amount", refund.amount, "status", refund.status,
                "payment_intent", refund.intentId, "metadata", refund.metadata);
    }

    private void emit(String type, Map<String, Object> object) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_fake_" + UUID.randomUUID().toString().replace("-", ""));
        event.put("object", "event");
        event.put("type", type);
        event.put("created", now);
        event.put("data", Map.of("object", object));
        String body = jsonMapper.writeValueAsString(event);
        Webhook webhook = new Webhook(type, body, StripeFormat.sign(webhookSecret, now, body));
        webhooks.add(webhook);
        if (webhookUrl != null) {
            Thread.ofVirtual().start(() -> deliver(webhook));
        }
    }

    /** Like a real provider: asynchronous, independent of the request that caused it. */
    private void deliver(Webhook webhook) {
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(webhookUrl)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header(StripeFormat.SIGNATURE_HEADER, webhook.signature())
                    .POST(HttpRequest.BodyPublishers.ofString(webhook.body())).build(), HttpResponse.BodyHandlers.discarding());
            log.atDebug().addKeyValue("webhookType", webhook.type()).addKeyValue("httpStatus", response.statusCode())
                    .log("Fake provider delivered a webhook");
        } catch (Exception e) {
            log.atWarn().addKeyValue("webhookType", webhook.type()).log("Fake provider could not deliver a webhook: " + e);
        }
    }

    /** For tests: lists every intent id created. */
    public synchronized List<String> intentIds() {
        return new ArrayList<>(intents.keySet());
    }
}
