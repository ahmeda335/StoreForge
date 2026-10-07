package dev.storeforge.payments.internal.domain;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The payment of one order at the provider.
 *
 * <pre>
 *   CREATED ──(provider intent created)──► PENDING ──(succeeded)──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *      │ (provider rejected)                 │ (cancelled)              └──────────────────────────────►┘
 *      ▼                                     ▼
 *    FAILED                              CANCELLED
 * </pre>
 *
 * The provider is the source of truth for money: a "succeeded" report is applied from ANY
 * non-captured state (even CANCELLED: the customer paid before our cancel reached the provider).
 * What to do about money the order can't keep is decided by the caller (compensation refund).
 * Invariants {@code refunded ≤ captured ≤ amount} are re-checked by the database.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    public enum Status { CREATED, PENDING, CAPTURED, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED }

    private static final Set<Status> CAPTURED_STATES = EnumSet.of(Status.CAPTURED, Status.PARTIALLY_REFUNDED, Status.REFUNDED);

    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID storeId;
    @Column(nullable = false)
    private UUID orderId;
    @Column(nullable = false)
    private String provider;
    private String providerPaymentId;
    private String clientSecret;
    @Column(nullable = false)
    private long amountMinor;
    @Column(columnDefinition = "bpchar(3)", nullable = false)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(nullable = false)
    private long capturedMinor;
    @Column(nullable = false)
    private long refundedMinor;
    private String failureCode;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    @Getter(AccessLevel.NONE)
    private Long version;

    public static Payment create(StoreId storeId, UUID orderId, String provider, long amountMinor, String currency, Instant now) {
        Payment payment = new Payment();
        payment.id = Ids.newId();
        payment.storeId = storeId.value();
        payment.orderId = orderId;
        payment.provider = provider;
        payment.amountMinor = amountMinor;
        payment.currency = currency;
        payment.status = Status.CREATED;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    /** The provider created its side. Idempotent for the same provider id (a retried creation). */
    public void attach(String providerPaymentId, @Nullable String clientSecret, Instant now) {
        if (this.providerPaymentId != null) {
            if (!this.providerPaymentId.equals(providerPaymentId)) {
                throw new IllegalStateException("Payment " + id + " is already attached to " + this.providerPaymentId);
            }
            return;
        }
        this.providerPaymentId = providerPaymentId;
        if (status == Status.CREATED) {
            this.clientSecret = clientSecret;
            status = Status.PENDING;
        }
        updatedAt = now;
    }

    /** @return false if already captured (a duplicate or late report) */
    public boolean capture(Instant now) {
        if (CAPTURED_STATES.contains(status)) {
            return false;
        }
        status = Status.CAPTURED;
        capturedMinor = amountMinor;
        clientSecret = null;   // nothing left to confirm with it
        failureCode = null;
        updatedAt = now;
        return true;
    }

    /** A declined attempt. The customer may try another card: the payment stays PENDING. */
    public boolean recordFailure(String code, Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        failureCode = code;
        updatedAt = now;
        return true;
    }

    /** The provider refused to create the payment at all. */
    public void fail(String code, Instant now) {
        if (status == Status.CREATED) {
            status = Status.FAILED;
            failureCode = code;
            updatedAt = now;
        }
    }

    /** @return false if it can no longer be cancelled (captured, or already final) */
    public boolean cancel(Instant now) {
        if (status != Status.CREATED && status != Status.PENDING) {
            return false;
        }
        status = Status.CANCELLED;
        clientSecret = null;
        updatedAt = now;
        return true;
    }

    public boolean open() {
        return status == Status.CREATED || status == Status.PENDING;
    }

    public boolean refundable() {
        return status == Status.CAPTURED || status == Status.PARTIALLY_REFUNDED;
    }

    /** What can still be refunded, given refunds already in flight. */
    public long refundableMinor(long pendingRefundsMinor) {
        return capturedMinor - refundedMinor - pendingRefundsMinor;
    }

    public void recordRefund(long amount, Instant now) {
        if (!refundable() || amount <= 0 || refundedMinor + amount > capturedMinor) {
            throw new IllegalStateException("Refund of " + amount + " does not fit payment " + id);
        }
        refundedMinor += amount;
        status = refundedMinor == capturedMinor ? Status.REFUNDED : Status.PARTIALLY_REFUNDED;
        updatedAt = now;
    }

    /** Reconciliation looked at it: don't look again before the next interval. */
    public void touch(Instant now) {
        updatedAt = now;
    }
}
