package dev.storeforge.stores.internal.web;

import dev.storeforge.audit.AuditEntry;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.internal.domain.Role;
import dev.storeforge.stores.internal.domain.Store;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class StoreDtos {

    private StoreDtos() {
    }

    record CreateStoreRequest(
            @NotBlank @Size(max = 40) String handle,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like USD") String currency) {
    }

    record RenameStoreRequest(@NotBlank @Size(max = 100) String name) {
    }

    record StoreResponse(UUID id, String handle, String name, String status, String currency, Instant createdAt) {
        static StoreResponse from(Store store) {
            return new StoreResponse(store.id().value(), store.handle(), store.name(), store.status().name(),
                    store.currency(), store.createdAt());
        }
    }

    record StoreSummaryResponse(UUID id, String handle, String name, String status) {
    }

    record MemberResponse(UUID userId, String email, UUID roleId, String roleName, boolean owner, Instant joinedAt) {
    }

    record ChangeRoleRequest(@NotNull UUID roleId) {
    }

    record RoleResponse(UUID id, String name, boolean owner, List<String> permissions) {
        static RoleResponse from(Role role) {
            return new RoleResponse(role.id(), role.name(), role.isOwner(),
                    role.permissions().stream().map(Permission::code).sorted().toList());
        }
    }

    record CreateRoleRequest(@NotBlank @Size(max = 50) String name, @NotNull @Size(max = 50) List<@NotBlank String> permissions) {
    }

    record InviteRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull UUID roleId) {
    }

    record InvitationResponse(UUID id, String email, UUID roleId, Instant expiresAt) {
    }

    record AcceptInvitationRequest(@NotBlank @Size(max = 128) String token) {
        @Override
        public String toString() {
            return "AcceptInvitationRequest[REDACTED]";
        }
    }

    record AuditEntryResponse(UUID id, String actorType, UUID actorId, String action, String resourceType,
                              String resourceId, Map<String, Object> details, String requestId, Instant occurredAt) {
        static AuditEntryResponse from(AuditEntry entry) {
            return new AuditEntryResponse(entry.id(), entry.actorType().name(), entry.actorId(), entry.action(),
                    entry.resourceType(), entry.resourceId(), entry.details(), entry.requestId(), entry.occurredAt());
        }
    }
}
