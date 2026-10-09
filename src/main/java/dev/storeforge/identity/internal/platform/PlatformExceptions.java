package dev.storeforge.identity.internal.platform;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

final class PlatformExceptions {

    private PlatformExceptions() {
    }

    /** Not a platform admin: 404, as if the platform API didn't exist for this user. */
    static class NotAPlatformAdmin extends StoreForgeException {
        NotAPlatformAdmin() {
            super(HttpStatus.NOT_FOUND, "not_found", "Not found.");
        }
    }

    static class AlreadyEnrolled extends StoreForgeException {
        AlreadyEnrolled() {
            super(HttpStatus.CONFLICT, "mfa_already_enrolled", "A second factor is already set up. Ask another platform admin to reset it.");
        }
    }

    static class NotEnrolled extends StoreForgeException {
        NotEnrolled() {
            super(HttpStatus.CONFLICT, "mfa_not_enrolled", "Set up the authenticator first (POST /platform/v1/mfa/enrollment).");
        }
    }

    /** Wrong, expired or already used code: one answer for all of them. */
    static class InvalidCode extends StoreForgeException {
        InvalidCode() {
            super(HttpStatus.UNAUTHORIZED, "invalid_mfa_code", "The code is not valid.");
        }
    }
}
