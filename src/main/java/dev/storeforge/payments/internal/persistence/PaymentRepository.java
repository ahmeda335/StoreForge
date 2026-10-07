package dev.storeforge.payments.internal.persistence;

import dev.storeforge.payments.internal.domain.Payment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Lock order everywhere: PAYMENT row, then (via the orders API) the ORDER row. Never the reverse,
 * so payment flows can't deadlock with each other.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByStoreIdAndOrderId(UUID storeId, UUID orderId);

    Optional<Payment> findByStoreIdAndId(UUID storeId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.storeId = :storeId and p.orderId = :orderId")
    Optional<Payment> lockByOrder(UUID storeId, UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.storeId = :storeId and p.id = :id")
    Optional<Payment> lock(UUID storeId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.storeId = :storeId and p.provider = :provider and p.providerPaymentId = :providerPaymentId")
    Optional<Payment> lockByProviderPaymentId(UUID storeId, String provider, String providerPaymentId);

    @Query("select p.id from Payment p where p.storeId = :storeId and p.status in :statuses and p.updatedAt < :cutoff order by p.updatedAt")
    List<UUID> stale(UUID storeId, Collection<Payment.Status> statuses, Instant cutoff, Limit limit);
}
