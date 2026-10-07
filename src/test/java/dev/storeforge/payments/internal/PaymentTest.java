package dev.storeforge.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.payments.internal.domain.Payment;
import dev.storeforge.payments.internal.domain.Payment.Status;
import dev.storeforge.shared.tenancy.StoreId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    static Payment pending() {
        Payment payment = Payment.create(StoreId.of(UUID.randomUUID()), UUID.randomUUID(), "fake", 2_000, "USD", NOW);
        payment.attach("pi_1", "pi_1_secret", NOW);
        return payment;
    }

    @Test
    void createdThenAttachedThenCapturedOnce() {
        Payment payment = pending();
        assertThat(payment.status()).isEqualTo(Status.PENDING);
        assertThat(payment.clientSecret()).isEqualTo("pi_1_secret");

        payment.attach("pi_1", "pi_1_secret", NOW);   // retried creation: same intent, no change
        assertThat(payment.capture(NOW)).isTrue();
        assertThat(payment.capture(NOW)).as("duplicate report").isFalse();

        assertThat(payment.capturedMinor()).isEqualTo(2_000);
        assertThat(payment.clientSecret()).as("no longer needed once paid").isNull();
        assertThatThrownBy(() -> payment.attach("pi_other", null, NOW)).isInstanceOf(IllegalStateException.class);
    }

    /** The provider is the source of truth for money: a success after our cancel still counts (and is then refunded). */
    @Test
    void aCaptureReportedAfterCancellationIsStillApplied() {
        Payment payment = pending();
        assertThat(payment.cancel(NOW)).isTrue();
        assertThat(payment.capture(NOW)).isTrue();
        assertThat(payment.status()).isEqualTo(Status.CAPTURED);
        assertThat(payment.cancel(NOW)).as("captured money can't be cancelled, only refunded").isFalse();
    }

    @Test
    void declinesKeepThePaymentOpenForAnotherCard() {
        Payment payment = pending();
        assertThat(payment.recordFailure("card_declined", NOW)).isTrue();
        assertThat(payment.status()).isEqualTo(Status.PENDING);
        assertThat(payment.failureCode()).isEqualTo("card_declined");
        payment.capture(NOW);
        assertThat(payment.failureCode()).isNull();
    }

    @Test
    void refundsNeverExceedTheCapturedAmount() {
        Payment payment = pending();
        assertThatThrownBy(() -> payment.recordRefund(100, NOW)).as("nothing captured yet").isInstanceOf(IllegalStateException.class);
        payment.capture(NOW);

        assertThat(payment.refundableMinor(500)).as("in-flight refunds count").isEqualTo(1_500);
        payment.recordRefund(500, NOW);
        assertThat(payment.status()).isEqualTo(Status.PARTIALLY_REFUNDED);
        assertThatThrownBy(() -> payment.recordRefund(1_501, NOW)).isInstanceOf(IllegalStateException.class);
        payment.recordRefund(1_500, NOW);
        assertThat(payment.status()).isEqualTo(Status.REFUNDED);
        assertThat(payment.refundable()).isFalse();
    }
}
