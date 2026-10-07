package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.UserDirectory;
import dev.storeforge.identity.internal.domain.User;
import dev.storeforge.identity.internal.persistence.UserRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class JpaUserDirectory implements UserDirectory {

    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> find(UUID userId) {
        return users.findById(userId).map(JpaUserDirectory::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEmail(String normalizedEmail) {
        return users.findByEmail(normalizedEmail).map(JpaUserDirectory::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findAll(Collection<UUID> userIds) {
        return users.findAllById(userIds).stream()
                .map(JpaUserDirectory::summary)
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    private static UserSummary summary(User user) {
        return new UserSummary(user.id(), user.email().value(), user.emailVerified());
    }
}
