package dev.storeforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.payments.internal.provider.FakePaymentProvider;
import dev.storeforge.payments.internal.provider.FakePaymentProvider.Webhook;
import dev.storeforge.payments.internal.provider.StripeFormat;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows.Placed;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The three parties of a card payment: the shopper's browser (storefront API + the provider's card
 * form), the provider (the fake, which also produces the webhooks), and the merchant (admin API).
 * Webhooks are delivered explicitly by the test, so it can duplicate, drop, delay or forge them.
 */
public final class PaymentFlows {

    private final HttpTestClient api;
    private final StorefrontFlows storefront;
    private final FakePaymentProvider provider;
    private final Owner shop;

    public PaymentFlows(HttpTestClient api, Owner shop, FakePaymentProvider provider) {
        this.api = api;
        this.shop = shop;
        this.storefront = new StorefrontFlows(api, shop);
        this.provider = provider;
    }

    public StorefrontFlows storefront() {
        return storefront;
    }

    public HttpResponse<String> startPayment(Placed placed) {
        return api.post("/storefront/v1/checkouts/" + placed.checkoutId() + "/payment", "", storefront.headers(placed.cart().token()));
    }

    /** Payment started successfully; returns the session (paymentId, status, amount, clientSecret). */
    public JsonNode started(Placed placed) {
        HttpResponse<String> response = startPayment(placed);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return Json.parse(response.body());
    }

    public JsonNode paymentStatus(Placed placed) {
        HttpResponse<String> response = api.get("/storefront/v1/checkouts/" + placed.checkoutId() + "/payment",
                storefront.headers(placed.cart().token()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return Json.parse(response.body());
    }

    /** Like Stripe: the client secret starts with the PaymentIntent id. */
    public static String intentId(JsonNode session) {
        String secret = session.get("clientSecret").asString();
        return secret.substring(0, secret.indexOf("_secret_"));
    }

    /** The shopper types a card into the provider's form; returns the webhooks the provider sends. */
    public List<Webhook> pay(JsonNode session, String card) {
        return provider.confirm(intentId(session), card);
    }

    public HttpResponse<String> deliver(Webhook webhook) {
        return api.post("/webhooks/v1/payments/fake", webhook.body(), Map.of(StripeFormat.SIGNATURE_HEADER, webhook.signature()));
    }

    public void deliverAll(List<Webhook> webhooks) {
        for (Webhook webhook : webhooks) {
            HttpResponse<String> response = deliver(webhook);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
    }

    public JsonNode order(UUID orderId) {
        HttpResponse<String> response = api.get("/admin/v1/stores/" + shop.storeId() + "/orders/" + orderId, shop.auth());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return Json.parse(response.body());
    }

    public JsonNode merchantPayment(UUID orderId) {
        HttpResponse<String> response = api.get("/admin/v1/stores/" + shop.storeId() + "/orders/" + orderId + "/payment", shop.auth());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return Json.parse(response.body());
    }

    public HttpResponse<String> refund(UUID orderId, String amount, String idempotencyKey) {
        return refund(shop.auth(), orderId, amount, idempotencyKey);
    }

    public HttpResponse<String> refund(Map<String, String> auth, UUID orderId, String amount, String idempotencyKey) {
        Map<String, String> headers = new java.util.HashMap<>(auth);
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + orderId + "/refunds",
                "{\"amount\":\"" + amount + "\",\"reason\":\"customer_request\"}", headers);
    }

    public List<String> timeline(UUID orderId) {
        return order(orderId).get("timeline").valueStream().map(entry -> entry.get("type").asString()).toList();
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }
}
