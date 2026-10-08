package dev.storeforge.stores.internal.application;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.Actor;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.PermissionDeniedException;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.StoreMember;
import dev.storeforge.stores.StoreNotFoundException;
import dev.storeforge.stores.internal.domain.StoreStatus;
import dev.storeforge.stores.internal.persistence.MemberStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class DefaultStoreAccess implements StoreAccess {

    private final MemberStore members;

    /** MANDATORY: the caller's tenant transaction must already be open (roles are behind RLS). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public StoreMember require(UUID userId, StoreId storeId, Permission... permissions) {
        var membership = members.findMembership(storeId, userId).orElseThrow(StoreNotFoundException::new);
        if (!StoreStatus.ACTIVE.name().equals(membership.storeStatus())) {
            throw new StoreExceptions.StoreSuspended();
        }
        StoreMember member = membership.member();
        for (Permission permission : permissions) {
            if (!member.has(permission)) {
                throw new PermissionDeniedException("Missing permission " + permission.code() + ".");
            }
        }
        return member;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void require(Actor actor, StoreId storeId, Permission... permissions) {
        switch (actor) {
            case Actor.User user -> require(user.id(), storeId, permissions);
            case Actor.ApiClient client -> {
                if (!client.storeId().equals(storeId)) {
                    throw new StoreNotFoundException();
                }
                for (Permission permission : permissions) {
                    if (!client.permissions().contains(permission)) {
                        throw new PermissionDeniedException("The credential's scopes don't allow " + permission.code() + ".");
                    }
                }
            }
        }
    }
}
