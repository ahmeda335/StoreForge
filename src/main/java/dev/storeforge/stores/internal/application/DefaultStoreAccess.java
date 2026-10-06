package dev.storeforge.stores.internal.application;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.PermissionDeniedException;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.StoreMember;
import dev.storeforge.stores.StoreNotFoundException;
import dev.storeforge.stores.internal.domain.StoreStatus;
import dev.storeforge.stores.internal.persistence.MemberStore;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class DefaultStoreAccess implements StoreAccess {

    private final MemberStore members;

    DefaultStoreAccess(MemberStore members) {
        this.members = members;
    }

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
}
