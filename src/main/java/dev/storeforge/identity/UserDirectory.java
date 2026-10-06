package dev.storeforge.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only lookup of platform users for other modules (e.g. showing staff emails). */
public interface UserDirectory {

    record UserSummary(UUID id, String email, boolean emailVerified) {
    }

    Optional<UserSummary> find(UUID userId);

    /** @param normalizedEmail lower-cased, trimmed (see {@code EmailAddress}) */
    Optional<UserSummary> findByEmail(String normalizedEmail);

    Map<UUID, UserSummary> findAll(Collection<UUID> userIds);
}
