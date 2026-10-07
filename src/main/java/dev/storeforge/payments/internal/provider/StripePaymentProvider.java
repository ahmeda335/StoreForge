package dev.storeforge.payments.internal.provider;

import io.micrometer.observation.ObservationRegistry;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe PaymentIntents over plain HTTP (form-encoded requests, JSON responses). No SDK: the
 * surface we use is five endpoints, and this way the contract (headers, fields, error mapping) is
 * visible and pinned by WireMock contract tests.
 *
 * <p>Error mapping is the important part:
 * <ul>
 *   <li>connect failure / connect timeout / 429 → unavailable, NOT performed (retry later);</li>
 *   <li>read timeout / 5xx / 409 (concurrent use of the key) → unavailable, outcome UNKNOWN (retry with the same key);</li>
 *   <li>other 4xx (card declined, invalid state, amount too large) → rejected, final.</li>
 * </ul>
 */
public class StripePaymentProvider implements PaymentProvider {

    public static final String NAME = "stripe";

    private final RestClient rest;
    private final String webhookSecret;
    private final Duration webhookTolerance;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public StripePaymentProvider(URI baseUrl, String secretKey, @Nullable String apiVersion, String webhookSecret,
                                 Duration connectTimeout, Duration readTimeout, Duration webhookTolerance,
                                 ObservationRegistry observations, JsonMapper jsonMapper, Clock clock) {
        if (secretKey == null || secretKey.isBlank() || webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("storeforge.payments.stripe.secret-key and webhook-secret are required for the Stripe provider");
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .observationRegistry(observations)   // client spans + http.client.requests metrics
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey);
        if (apiVersion != null && !apiVersion.isBlank()) {
            builder.defaultHeader("Stripe-Version", apiVersion);
        }
        this.rest = builder.build();
        this.webhookSecret = webhookSecret;
        this.webhookTolerance = webhookTolerance;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderPayment createPayment(CreatePayment request, String idempotencyKey) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", Long.toString(request.amountMinor()));
        form.add("currency", request.currency().toLowerCase(Locale.ROOT));
        form.add("automatic_payment_methods[enabled]", "true");
        form.add("description", "Order #" + request.orderNumber());
        form.add("metadata[store_id]", request.storeId().toString());
        form.add("metadata[payment_id]", request.paymentId().toString());
        form.add("metadata[order_id]", request.orderId().toString());
        return StripeFormat.payment(post("create_payment", "/v1/payment_intents", form, idempotencyKey));
    }

    @Override
    public ProviderPayment retrievePayment(String providerPaymentId) {
        return StripeFormat.payment(get("retrieve_payment", "/v1/payment_intents/{id}", providerPaymentId));
    }

    @Override
    public ProviderPayment cancelPayment(String providerPaymentId, String idempotencyKey) {
        return StripeFormat.payment(post("cancel_payment", "/v1/payment_intents/" + providerPaymentId + "/cancel",
                new LinkedMultiValueMap<>(), idempotencyKey));
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, long amountMinor, UUID storeId, UUID refundId, String idempotencyKey) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", providerPaymentId);
        form.add("amount", Long.toString(amountMinor));
        form.add("metadata[store_id]", storeId.toString());
        form.add("metadata[refund_id]", refundId.toString());
        return StripeFormat.refund(post("refund", "/v1/refunds", form, idempotencyKey));
    }

    @Override
    public ProviderRefund retrieveRefund(String providerRefundId) {
        return StripeFormat.refund(get("retrieve_refund", "/v1/refunds/{id}", providerRefundId));
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

    private JsonNode post(String operation, String path, MultiValueMap<String, String> form, String idempotencyKey) {
        return call(operation, () -> rest.post().uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Idempotency-Key", idempotencyKey)
                .body(form)
                .exchange((request, response) -> read(operation, response.getStatusCode(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))));
    }

    private JsonNode get(String operation, String path, String id) {
        return call(operation, () -> rest.get().uri(path, id)
                .exchange((request, response) -> read(operation, response.getStatusCode(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))));
    }

    private JsonNode read(String operation, HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) {
            return jsonMapper.readTree(body);
        }
        JsonNode error = parseQuietly(body).path("error");
        String code = StripeFormat.text(error, "decline_code");
        if (code == null) {
            code = StripeFormat.text(error, "code");
        }
        if (code == null) {
            code = StripeFormat.text(error, "type");
        }
        String message = code == null ? "HTTP " + status.value() : code;
        int value = status.value();
        if (value == 429) {
            throw new ProviderUnavailableException(operation, false, "rate limited", null);
        }
        if (value == 409 || status.is5xxServerError()) {
            throw new ProviderUnavailableException(operation, true, "HTTP " + value, null);
        }
        throw new ProviderRejectedException(code == null ? "http_" + value : code, message);
    }

    private JsonNode parseQuietly(String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (tools.jackson.core.JacksonException e) {
            return jsonMapper.createObjectNode();
        }
    }

    private static JsonNode call(String operation, Supplier<JsonNode> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            boolean notSent = cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException;
            boolean timedOut = cause instanceof HttpTimeoutException;
            throw new ProviderUnavailableException(operation, !notSent,
                    notSent ? "connection failed" : timedOut ? "read timeout" : "I/O error", e);
        } catch (java.io.UncheckedIOException e) {
            throw new ProviderUnavailableException(operation, true, "I/O error", e);
        }
    }
}
