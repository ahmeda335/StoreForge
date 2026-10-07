package dev.storeforge.stores.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.shared.error.ConstraintViolations;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.PermissionDeniedException;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.StoreMember;
import dev.storeforge.stores.internal.domain.Role;
import dev.storeforge.stores.internal.persistence.RoleRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roles;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final Clock clock;

    public List<Role> list(UUID userId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.STAFF_READ);
            return roles.findByStoreIdOrderByName(storeId.value());
        });
    }

    public Role create(UUID userId, StoreId storeId, String name, Collection<String> permissionCodes) {
        Set<Permission> permissions = parse(permissionCodes);
        try {
            return tenant.inStore(storeId, () -> {
                StoreMember actor = access.require(userId, storeId, Permission.ROLES_MANAGE);
                if (!actor.covers(false, permissions)) {
                    throw new PermissionDeniedException("You cannot create a role with permissions you do not have.");
                }
                Role role = roles.saveAndFlush(Role.custom(storeId, name.trim(), permissions, clock.instant()));
                audit.recordUserAction(storeId, userId, "role.created", "role", role.id().toString(),
                        Map.of("name", role.name(), "permissions", permissions.stream().map(Permission::code).sorted().toList()));
                return role;
            });
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isUniqueViolation(e, "uq_roles_store_name")) {
                throw new StoreExceptions.RoleNameTaken();
            }
            throw e;
        }
    }

    public void delete(UUID userId, StoreId storeId, UUID roleId) {
        try {
            tenant.runInStore(storeId, () -> {
                StoreMember actor = access.require(userId, storeId, Permission.ROLES_MANAGE);
                Role role = roles.findByStoreIdAndId(storeId.value(), roleId).orElseThrow(StoreExceptions.RoleNotFound::new);
                if (role.isOwner()) {
                    throw new StoreExceptions.RoleProtected();
                }
                if (!actor.covers(false, role.permissions())) {
                    throw new PermissionDeniedException("You cannot delete a role with permissions you do not have.");
                }
                roles.delete(role);
                roles.flush();   // surface the foreign-key violation (role still assigned) here, inside the try
                audit.recordUserAction(storeId, userId, "role.deleted", "role", roleId.toString(), Map.of("name", role.name()));
            });
        } catch (DataIntegrityViolationException e) {
            // The database, not a racy pre-check, guarantees members never point at a deleted role.
            if (ConstraintViolations.isForeignKeyViolation(e, "fk_store_members_role")) {
                throw new StoreExceptions.RoleInUse();
            }
            throw e;
        }
    }

    static Set<Permission> parse(Collection<String> codes) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String code : codes) {
            permissions.add(Permission.fromCode(code).orElseThrow(() -> new StoreExceptions.UnknownPermission(code)));
        }
        return permissions;
    }
}
