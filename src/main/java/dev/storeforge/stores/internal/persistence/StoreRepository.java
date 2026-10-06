package dev.storeforge.stores.internal.persistence;

import dev.storeforge.stores.internal.domain.Store;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, UUID> {
}
