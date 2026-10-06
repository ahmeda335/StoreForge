package dev.storeforge.identity.internal.persistence;

import dev.storeforge.identity.internal.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** @param normalizedEmail must come from {@code EmailAddress#value()} (see its Javadoc) */
    Optional<User> findByEmail(String normalizedEmail);
}
