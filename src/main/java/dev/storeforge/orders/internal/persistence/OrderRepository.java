package dev.storeforge.orders.internal.persistence;

import dev.storeforge.orders.internal.domain.Order;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByStoreIdAndId(UUID storeId, UUID id);

    /**
     * SELECT ... FOR UPDATE: state transitions (pay, cancel, fulfill, expire) on one order run one
     * after another. Without the lock, "pay" and "cancel" could both read OPEN/PENDING and both
     * succeed (the @Version check would reject the second write, but only after both did their
     * inventory side effects: release AND commit of the same reservation).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.storeId = :storeId and o.id = :id")
    Optional<Order> lockByStoreIdAndId(UUID storeId, UUID id);
}
