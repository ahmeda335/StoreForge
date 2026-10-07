package dev.storeforge.stores.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.internal.application.InvitationService;
import dev.storeforge.stores.internal.application.MemberService;
import dev.storeforge.stores.internal.application.RoleService;
import dev.storeforge.stores.internal.web.StoreDtos.AcceptInvitationRequest;
import dev.storeforge.stores.internal.web.StoreDtos.ChangeRoleRequest;
import dev.storeforge.stores.internal.web.StoreDtos.CreateRoleRequest;
import dev.storeforge.stores.internal.web.StoreDtos.InvitationResponse;
import dev.storeforge.stores.internal.web.StoreDtos.InviteRequest;
import dev.storeforge.stores.internal.web.StoreDtos.MemberResponse;
import dev.storeforge.stores.internal.web.StoreDtos.RoleResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Members, roles and invitations of one store. Every endpoint is tenant-scoped by {storeId}. */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}")
@RequiredArgsConstructor
class StaffController {

    private final MemberService members;
    private final RoleService roles;
    private final InvitationService invitations;

    @GetMapping("/members")
    Map<String, List<MemberResponse>> members(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        return Map.of("data", members.list(user.userId(), StoreId.of(storeId)).stream()
                .map(details -> new MemberResponse(details.member().userId(), details.email(), details.member().roleId(),
                        details.member().roleName(), details.member().owner(), details.member().joinedAt()))
                .toList());
    }

    @PatchMapping("/members/{userId}")
    ResponseEntity<Void> changeRole(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                    @PathVariable UUID userId, @Valid @RequestBody ChangeRoleRequest body) {
        members.changeRole(user.userId(), StoreId.of(storeId), userId, body.roleId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/members/{userId}")
    ResponseEntity<Void> removeMember(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID userId) {
        members.remove(user.userId(), StoreId.of(storeId), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles")
    Map<String, List<RoleResponse>> roles(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        return Map.of("data", roles.list(user.userId(), StoreId.of(storeId)).stream().map(RoleResponse::from).toList());
    }

    @PostMapping("/roles")
    ResponseEntity<RoleResponse> createRole(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                            @Valid @RequestBody CreateRoleRequest body) {
        var role = roles.create(user.userId(), StoreId.of(storeId), body.name(), body.permissions());
        return ResponseEntity.created(URI.create("/admin/v1/stores/" + storeId + "/roles/" + role.id()))
                .body(RoleResponse.from(role));
    }

    @DeleteMapping("/roles/{roleId}")
    ResponseEntity<Void> deleteRole(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID roleId) {
        roles.delete(user.userId(), StoreId.of(storeId), roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invitations")
    Map<String, List<InvitationResponse>> invitations(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        return Map.of("data", invitations.listPending(user.userId(), StoreId.of(storeId)).stream()
                .map(invitation -> new InvitationResponse(invitation.id(), invitation.email(), invitation.roleId(), invitation.expiresAt()))
                .toList());
    }

    @PostMapping("/invitations")
    ResponseEntity<InvitationResponse> invite(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                              @Valid @RequestBody InviteRequest body) {
        var created = invitations.invite(user.userId(), StoreId.of(storeId), body.email(), body.roleId());
        return ResponseEntity.status(201).body(new InvitationResponse(created.id(), created.email(), created.roleId(), created.expiresAt()));
    }

    @DeleteMapping("/invitations/{invitationId}")
    ResponseEntity<Void> revokeInvitation(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                          @PathVariable UUID invitationId) {
        invitations.revoke(user.userId(), StoreId.of(storeId), invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/acceptance")
    ResponseEntity<Void> accept(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                @Valid @RequestBody AcceptInvitationRequest body) {
        invitations.accept(user.userId(), StoreId.of(storeId), body.token());
        return ResponseEntity.noContent().build();
    }
}
