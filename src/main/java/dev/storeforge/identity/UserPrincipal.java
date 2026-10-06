package dev.storeforge.identity;

import java.util.UUID;

/**
 * The authenticated platform user behind a request. Authentication ends here: this proves WHO is
 * calling. Whether they may act on a given store or resource is decided by authorization checks in
 * the owning module's application services (P3).
 */
public record UserPrincipal(UUID userId) {
}
