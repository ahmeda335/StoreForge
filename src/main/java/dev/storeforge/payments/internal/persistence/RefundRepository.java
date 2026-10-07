package dev.storeforge.payments.internal.persistence;

import dev.storeforge.payments.internal.domain.Refund;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByStoreIdAndId(UUID storeId, UUID id);

    Optional<Refund> findByStoreIdAndIdempotencyKey(UUID storeId, String idempotencyKey);

    List<Refund> findByStoreIdAndPaymentIdOrderByCreatedAt(UUID storeId, UUID paymentId);

    /** Scalar on purpose: loading the entity before locking it would leave a stale copy in the persistence context. */
    @Query("select r.paymentId from Refund r where r.storeId = :storeId and r.id = :id")
    Optional<UUID> paymentIdOf(UUID storeId, UUID id);

    @Query("select r.id from Refund r where r.storeId = :storeId and r.providerRefundId = :providerRefundId")
    Optional<UUID> idByProviderRefundId(UUID storeId, String providerRefundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.storeId = :storeId and r.id = :id")
    Optional<Refund> lock(UUID storeId, UUID id);

    @Query("select coalesce(sum(r.amountMinor), 0) from Refund r where r.storeId = :storeId and r.paymentId = :paymentId"
            + " and r.status = dev.storeforge.payments.internal.domain.Refund.Status.PENDING")
    long pendingTotal(UUID storeId, UUID paymentId);

    @Query("select r.id from Refund r where r.storeId = :storeId and r.status = dev.storeforge.payments.internal.domain.Refund.Status.PENDING"
            + " and r.updatedAt < :cutoff order by r.updatedAt")
    List<UUID> stalePending(UUID storeId, Instant cutoff, Limit limit);
}
