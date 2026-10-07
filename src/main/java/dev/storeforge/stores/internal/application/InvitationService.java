package dev.storeforge.stores.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.identity.UserDirectory;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.email.EmailAddress;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.PermissionDeniedException;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.StoreMember;
import dev.storeforge.stores.internal.domain.Role;
import dev.storeforge.stores.internal.persistence.InvitationStore;
import dev.storeforge.stores.internal.persistence.InvitationStore.PendingInvitation;
import dev.storeforge.stores.internal.persistence.MemberStore;
import dev.storeforge.stores.internal.persistence.RoleRepository;
import dev.storeforge.stores.internal.persistence.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Staff invitations. The emailed link carries the store id and a single-use token in the URL
 * fragment. The store id lets acceptance run inside that store's tenant transaction, so the
 * RLS-protected invitation is visible without any cross-tenant lookup or RLS bypass.
 *
 * <p>An invitation is bound to an email address: only the user owning that (verified) address can
 * accept it, so a forwarded or leaked link is useless to anyone else.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    private final InvitationStore invitations;
    private final MemberStore members;
    private final RoleRepository roles;
    private final StoreRepository stores;
    private final StoreAccess access;
    private final UserDirectory users;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final ApplicationEventPublisher events;
    private final StoresProperties properties;
    private final Clock clock;

    public record CreatedInvitation(UUID id, String email, UUID roleId, Instant expiresAt) {
    }

    public CreatedInvitation invite(UUID actorId, StoreId storeId, String rawEmail, UUID roleId) {
        EmailAddress email = EmailAddress.of(rawEmail);
        return tenant.inStore(storeId, () -> {
            StoreMember actor = access.require(actorId, storeId, Permission.STAFF_MANAGE);
            Role role = roles.findByStoreIdAndId(storeId.value(), roleId).orElseThrow(StoreExceptions.RoleNotFound::new);
            if (!actor.covers(role.isOwner(), role.permissions())) {
                throw new PermissionDeniedException("You cannot invite someone into a role with permissions you do not have.");
            }
            boolean alreadyMember = users.findByEmail(email.value())
                    .map(existing -> members.exists(storeId, existing.id()))
                    .orElse(false);
            if (alreadyMember) {
                throw new StoreExceptions.AlreadyMember();
            }
            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.invitationTtl());
            String token = SecretTokens.generate();
            invitations.revokePendingFor(storeId, email.value(), now);
            UUID id = invitations.insert(storeId, email.value(), role.id(), SecretTokens.hash(token), actorId, expiresAt, now);
            String storeName = stores.findById(storeId.value()).orElseThrow().name();
            events.publishEvent(new InvitationEmail(storeId, id, email, storeName,
                    properties.appBaseUrl() + "/accept-invitation#store=" + storeId + "&token=" + token));
            audit.recordUserAction(storeId, actorId, "invitation.created", "invitation", id.toString(),
                    Map.of("email", email.value(), "role", role.name()));
            return new CreatedInvitation(id, email.value(), role.id(), expiresAt);
        });
    }

    public List<PendingInvitation> listPending(UUID actorId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(actorId, storeId, Permission.STAFF_READ);
            return invitations.listPending(storeId, clock.instant());
        });
    }

    public void revoke(UUID actorId, StoreId storeId, UUID invitationId) {
        tenant.runInStore(storeId, () -> {
            access.require(actorId, storeId, Permission.STAFF_MANAGE);
            if (invitations.revoke(storeId, invitationId, clock.instant()) == 0) {
                throw new StoreExceptions.InvitationNotFound();
            }
            audit.recordUserAction(storeId, actorId, "invitation.revoked", "invitation", invitationId.toString(), Map.of());
        });
    }

    /**
     * The caller is NOT a member yet, so there is no access check. The token is the authorization.
     * Every failure (unknown token, wrong store, expired, other email) is the same 404, and any
     * exception rolls back the token consumption, so a failed attempt never burns a valid invitation.
     */
    public void accept(UUID userId, StoreId storeId, String token) {
        var user = users.find(userId).orElseThrow(StoreExceptions.InvitationNotFound::new);
        tenant.runInStore(storeId, () -> {
            Instant now = clock.instant();
            var accepted = invitations.accept(storeId, SecretTokens.hash(token), userId, now)
                    .orElseThrow(StoreExceptions.InvitationNotFound::new);
            if (!accepted.email().equalsIgnoreCase(user.email()) || !user.emailVerified()) {
                throw new StoreExceptions.InvitationNotFound();
            }
            if (members.exists(storeId, userId)) {
                throw new StoreExceptions.AlreadyMember();
            }
            members.insert(storeId, userId, accepted.roleId(), now);
            audit.recordUserAction(storeId, userId, "invitation.accepted", "invitation", accepted.id().toString(),
                    Map.of("roleId", accepted.roleId().toString()));
        });
    }

    record InvitationEmail(StoreId storeId, UUID invitationId, EmailAddress to, String storeName, String link) {
        @Override
        public String toString() {
            return "InvitationEmail[REDACTED]";
        }
    }
}
