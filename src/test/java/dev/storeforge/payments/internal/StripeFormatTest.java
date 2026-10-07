package dev.storeforge.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.payments.internal.provider.InvalidWebhookException;
import dev.storeforge.payments.internal.provider.PaymentProvider.EventType;
import dev.storeforge.payments.internal.provider.PaymentProvider.RefundStatus;
import dev.storeforge.payments.internal.provider.StripeFormat;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Webhook signatures are the ONLY authentication of /webhooks: every way to get them wrong is a test. */
class StripeFormatTest {

    static final String SECRET = "whsec_test_secret";
    static final Duration TOLERANCE = Duration.ofMinutes(5);
    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\"}}}";

    /** Computed independently of StripeFormat.sign, so the test doesn't just check the code against itself. */
    static String header(String secret, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        StripeFormat.verify(BODY, header(SECRET, NOW.getEpochSecond(), BODY), SECRET, TOLERANCE, NOW);
        assertThat(StripeFormat.sign(SECRET, NOW.getEpochSecond(), BODY)).isEqualTo(header(SECRET, NOW.getEpochSecond(), BODY));
    }

    @Test
    void rejectsAModifiedBodyAWrongSecretAndMissingOrMalformedHeaders() throws Exception {
        String valid = header(SECRET, NOW.getEpochSecond(), BODY);
        assertRejected(BODY.replace("pi_1", "pi_2"), valid, "signature mismatch");
        assertRejected(BODY + " ", valid, "signature mismatch");   // even whitespace: the RAW bytes are signed
        assertRejected(BODY, header("whsec_other", NOW.getEpochSecond(), BODY), "signature mismatch");
        assertRejected(BODY, null, "missing signature header");
        assertRejected(BODY, "v1=abc", "malformed signature header");
        assertRejected(BODY, "t=abc,v1=abc", "malformed timestamp");
    }

    /** A captured request replayed later is refused even though its signature is valid. */
    @Test
    void rejectsTimestampsOutsideTheTolerance() throws Exception {
        long sixMinutesAgo = NOW.minus(Duration.ofMinutes(6)).getEpochSecond();
        long sixMinutesAhead = NOW.plus(Duration.ofMinutes(6)).getEpochSecond();
        assertRejected(BODY, header(SECRET, sixMinutesAgo, BODY), "timestamp outside tolerance");
        assertRejected(BODY, header(SECRET, sixMinutesAhead, BODY), "timestamp outside tolerance");
        StripeFormat.verify(BODY, header(SECRET, NOW.minus(Duration.ofMinutes(4)).getEpochSecond(), BODY), SECRET, TOLERANCE, NOW);
    }

    /** During secret rotation the provider sends several v1 signatures; one valid is enough. */
    @Test
    void acceptsAnyOfSeveralSignatures() throws Exception {
        String rotated = header(SECRET, NOW.getEpochSecond(), BODY).replace(",v1=", ",v1=deadbeef,v1=");
        StripeFormat.verify(BODY, rotated, SECRET, TOLERANCE, NOW);
    }

    @Test
    void parsesEventsWithOurMetadata() {
        UUID store = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID refund = UUID.randomUUID();
        JsonMapper json = JsonMapper.builder().build();

        var failed = StripeFormat.parseEvent(json.readTree("""
                {"id":"evt_2","type":"payment_intent.payment_failed","data":{"object":{"id":"pi_9","object":"payment_intent",
                 "metadata":{"store_id":"%s","payment_id":"%s"},"last_payment_error":{"code":"card_declined","decline_code":"insufficient_funds"}}}}
                """.formatted(store, payment)));
        assertThat(failed.type()).isEqualTo(EventType.PAYMENT_FAILED);
        assertThat(failed.storeId()).isEqualTo(store);
        assertThat(failed.paymentId()).isEqualTo(payment);
        assertThat(failed.providerPaymentId()).isEqualTo("pi_9");
        assertThat(failed.failureCode()).isEqualTo("insufficient_funds");

        var refunded = StripeFormat.parseEvent(json.readTree("""
                {"id":"evt_3","type":"refund.updated","data":{"object":{"id":"re_1","object":"refund","status":"succeeded",
                 "payment_intent":"pi_9","metadata":{"store_id":"%s","refund_id":"%s"}}}}
                """.formatted(store, refund)));
        assertThat(refunded.type()).isEqualTo(EventType.REFUND_UPDATED);
        assertThat(refunded.refundId()).isEqualTo(refund);
        assertThat(refunded.providerRefundId()).isEqualTo("re_1");
        assertThat(refunded.providerPaymentId()).isEqualTo("pi_9");
        assertThat(refunded.refundStatus()).isEqualTo(RefundStatus.SUCCEEDED);

        var other = StripeFormat.parseEvent(json.readTree("""
                {"id":"evt_4","type":"customer.created","data":{"object":{"id":"cus_1","metadata":{"store_id":"not-a-uuid"}}}}"""));
        assertThat(other.type()).isEqualTo(EventType.IGNORED);
        assertThat(other.storeId()).isNull();
    }

    private static void assertRejected(String body, String header, String reason) {
        assertThatThrownBy(() -> StripeFormat.verify(body, header, SECRET, TOLERANCE, NOW))
                .isInstanceOf(InvalidWebhookException.class).hasMessage(reason);
    }
}
