package dev.storeforge.identity.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.identity.internal.persistence.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own profile: the first endpoint protected by an access token. */
@RestController
class MeController {

    private final UserRepository users;

    MeController(UserRepository users) {
        this.users = users;
    }

    record MeResponse(UUID id, String email, boolean emailVerified, Instant createdAt) {
    }

    @GetMapping("/admin/v1/me")
    MeResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        var user = users.findById(principal.userId()).orElseThrow();
        return new MeResponse(user.id(), user.email().value(), user.emailVerified(), user.createdAt());
    }
}
