package dev.storeforge.payments.internal.domain;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A refund, recorded as PENDING and committed BEFORE the provider is called. Its id is the
 * provider idempotency key, so retries after a timeout refund at most once. The row (unique per
 * store + client {@code Idempotency-Key}) is also the merchant request's idempotency record.
 */
@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    /** Money the order never kept: captured after the order was cancelled, or paid twice. */
    public static final String COMPENSATION = "order_not_payable";

    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID storeId;
    @Column(nullable = false)
    private UUID paymentId;
    @Column(nullable = false)
    private long amountMinor;
    @Column(nullable = false)
    private String reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private String providerRefundId;
    @Column(nullable = false)
    private String idempotencyKey;
    private UUID createdBy;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public static Refund request(StoreId storeId, UUID paymentId, long amountMinor, String reason, String idempotencyKey,
                                 @Nullable UUID createdBy, Instant now) {
        Refund refund = new Refund();
        refund.id = Ids.newId();
        refund.storeId = storeId.value();
        refund.paymentId = paymentId;
        refund.amountMinor = amountMinor;
        refund.reason = reason;
        refund.status = Status.PENDING;
        refund.idempotencyKey = idempotencyKey;
        refund.createdBy = createdBy;
        refund.createdAt = now;
        refund.updatedAt = now;
        return refund;
    }

    public boolean compensation() {
        return COMPENSATION.equals(reason);
    }

    public void attachProviderRefund(String providerRefundId, Instant now) {
        if (this.providerRefundId == null) {
            this.providerRefundId = providerRefundId;
            updatedAt = now;
        }
    }

    /** @return false if no longer pending (duplicate report) */
    public boolean succeed(Instant now) {
        return finish(Status.SUCCEEDED, now);
    }

    public boolean fail(Instant now) {
        return finish(Status.FAILED, now);
    }

    private boolean finish(Status outcome, Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        status = outcome;
        updatedAt = now;
        return true;
    }
}
