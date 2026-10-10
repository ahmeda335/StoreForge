package dev.storeforge.identity.internal.persistence;

import dev.storeforge.identity.internal.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * @param normalizedEmail must come from {@code EmailAddress#value()} (see its Javadoc).
     * Native with a cast: a derived query binds a varchar, and {@code citext = varchar} compares as
     * TEXT, which can't use the citext unique index (every login would scan users; EXP-023).
     */
    @Query(value = "SELECT * FROM users WHERE email = CAST(:email AS citext)", nativeQuery = true)
    Optional<User> findByEmail(@Param("email") String normalizedEmail);
}
