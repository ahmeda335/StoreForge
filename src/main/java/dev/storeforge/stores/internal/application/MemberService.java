package dev.storeforge.stores.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.identity.UserDirectory;
import dev.storeforge.identity.UserDirectory.UserSummary;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.PermissionDeniedException;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.StoreMember;
import dev.storeforge.stores.internal.domain.Role;
import dev.storeforge.stores.internal.persistence.MemberStore;
import dev.storeforge.stores.internal.persistence.MemberStore.MemberView;
import dev.storeforge.stores.internal.persistence.RoleRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberStore members;
    private final RoleRepository roles;
    private final StoreAccess access;
    private final UserDirectory users;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final Clock clock;

    public record MemberDetails(MemberView member, String email) {
    }

    public List<MemberDetails> list(UUID userId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.STAFF_READ);
            List<MemberView> views = members.list(storeId);
            Map<UUID, UserSummary> summaries = users.findAll(views.stream().map(MemberView::userId).toList());
            return views.stream()
                    .map(view -> new MemberDetails(view, summaries.containsKey(view.userId()) ? summaries.get(view.userId()).email() : null))
                    .toList();
        });
    }

    /**
     * Rules, in order: actor has staff:manage; actor covers BOTH the target's current role (cannot
     * demote someone more powerful) and the new role (cannot grant more than they hold); the store
     * keeps at least one owner. The store row lock makes the owner count stable for this transaction.
     */
    public void changeRole(UUID actorId, StoreId storeId, UUID targetUserId, UUID newRoleId) {
        tenant.runInStore(storeId, () -> {
            StoreMember actor = access.require(actorId, storeId, Permission.STAFF_MANAGE);
            members.lockStore(storeId);
            StoreMember target = members.findMembership(storeId, targetUserId)
                    .orElseThrow(StoreExceptions.MemberNotFound::new).member();
            Role newRole = roles.findByStoreIdAndId(storeId.value(), newRoleId).orElseThrow(StoreExceptions.RoleNotFound::new);

            if (!actor.covers(target.owner(), target.permissions()) || !actor.covers(newRole.isOwner(), newRole.permissions())) {
                throw new PermissionDeniedException("You cannot assign or change a role with permissions you do not have.");
            }
            if (target.owner() && !newRole.isOwner() && members.countOwners(storeId) <= 1) {
                throw new StoreExceptions.LastOwner();
            }
            members.changeRole(storeId, targetUserId, newRoleId, clock.instant());
            audit.recordUserAction(storeId, actorId, "member.role_changed", "member", targetUserId.toString(),
                    Map.of("fromRole", target.roleName(), "toRole", newRole.name()));
        });
    }

    /** Members may always leave; removing someone else needs staff:manage and must not escalate. */
    public void remove(UUID actorId, StoreId storeId, UUID targetUserId) {
        tenant.runInStore(storeId, () -> {
            boolean leaving = actorId.equals(targetUserId);
            StoreMember actor = leaving ? access.require(actorId, storeId) : access.require(actorId, storeId, Permission.STAFF_MANAGE);
            members.lockStore(storeId);
            StoreMember target = members.findMembership(storeId, targetUserId)
                    .orElseThrow(StoreExceptions.MemberNotFound::new).member();
            if (!leaving && !actor.covers(target.owner(), target.permissions())) {
                throw new PermissionDeniedException("You cannot remove a member with permissions you do not have.");
            }
            if (target.owner() && members.countOwners(storeId) <= 1) {
                throw new StoreExceptions.LastOwner();
            }
            members.remove(storeId, targetUserId);
            audit.recordUserAction(storeId, actorId, leaving ? "member.left" : "member.removed", "member",
                    targetUserId.toString(), Map.of("role", target.roleName()));
        });
    }
}
