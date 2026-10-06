package dev.storeforge.stores.internal.application;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

final class StoreExceptions {

    private StoreExceptions() {
    }

    static class HandleUnavailable extends StoreForgeException {
        HandleUnavailable() {
            super(HttpStatus.CONFLICT, "handle_unavailable", "This store handle is not available.");
        }
    }

    static class InvalidHandle extends StoreForgeException {
        InvalidHandle() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_handle",
                    "Handles are 3-40 lowercase letters, digits or inner hyphens.");
        }
    }

    static class StoreSuspended extends StoreForgeException {
        StoreSuspended() {
            super(HttpStatus.FORBIDDEN, "store_suspended", "This store is not active.");
        }
    }

    static class MemberNotFound extends StoreForgeException {
        MemberNotFound() {
            super(HttpStatus.NOT_FOUND, "member_not_found", "Member not found.");
        }
    }

    static class RoleNotFound extends StoreForgeException {
        RoleNotFound() {
            super(HttpStatus.NOT_FOUND, "role_not_found", "Role not found.");
        }
    }

    static class RoleNameTaken extends StoreForgeException {
        RoleNameTaken() {
            super(HttpStatus.CONFLICT, "role_name_taken", "A role with this name already exists.");
        }
    }

    static class RoleInUse extends StoreForgeException {
        RoleInUse() {
            super(HttpStatus.CONFLICT, "role_in_use", "The role is still assigned to members.");
        }
    }

    static class RoleProtected extends StoreForgeException {
        RoleProtected() {
            super(HttpStatus.CONFLICT, "role_protected", "The owner role cannot be deleted.");
        }
    }

    static class UnknownPermission extends StoreForgeException {
        UnknownPermission(String code) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_permission", "Unknown permission: " + code);
        }
    }

    static class LastOwner extends StoreForgeException {
        LastOwner() {
            super(HttpStatus.CONFLICT, "last_owner", "A store must keep at least one owner.");
        }
    }

    static class AlreadyMember extends StoreForgeException {
        AlreadyMember() {
            super(HttpStatus.CONFLICT, "already_member", "This person is already a member of the store.");
        }
    }

    static class InvitationNotFound extends StoreForgeException {
        InvitationNotFound() {
            super(HttpStatus.NOT_FOUND, "invitation_not_found", "The invitation is invalid, expired, or not addressed to you.");
        }
    }
}
