package dev.storeforge.stores.internal.persistence;

import dev.storeforge.stores.internal.domain.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant data: every finder takes the store id explicitly (application-level scoping). Row-level
 * security is the second, independent layer underneath.
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByStoreIdAndId(UUID storeId, UUID id);

    List<Role> findByStoreIdOrderByName(UUID storeId);
}
