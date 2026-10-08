package dev.storeforge.stores;

import dev.storeforge.audit.AuditEntry.ActorType;
import dev.storeforge.shared.tenancy.StoreId;
import java.util.Set;
import java.util.UUID;

/**
 * Who is acting on a store: a signed-in user (authorized by membership and role), or an API client
 * (authorized by the permissions its scopes grant, for exactly one store). Services that serve both
 * the admin API and the developer API take an Actor; {@link StoreAccess#require(Actor, StoreId, Permission...)}
 * decides for both, and audit entries record the real kind of actor.
 */
public sealed interface Actor {

    UUID id();

    ActorType auditType();

    static Actor user(UUID userId) {
        return new User(userId);
    }

    record User(UUID id) implements Actor {
        @Override
        public ActorType auditType() {
            return ActorType.USER;
        }
    }

    /**
     * @param id          the credential (API key or app installation) acting
     * @param storeId     the only store it may act on
     * @param permissions what its scopes allow (never more than the merchant who granted them held)
     */
    record ApiClient(UUID id, StoreId storeId, Kind kind, Set<Permission> permissions) implements Actor {

        public enum Kind { API_KEY, APP }

        public ApiClient {
            permissions = Set.copyOf(permissions);
        }

        @Override
        public ActorType auditType() {
            return kind == Kind.API_KEY ? ActorType.API_KEY : ActorType.APP;
        }
    }
}
