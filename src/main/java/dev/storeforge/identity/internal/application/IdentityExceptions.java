package dev.storeforge.identity.internal.application;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

/** Expected identity failures. Messages are deliberately generic: they must not reveal whether an account exists. */
public final class IdentityExceptions {

    private IdentityExceptions() {
    }

    public static class InvalidCredentials extends StoreForgeException {
        public InvalidCredentials() {
            super(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect.");
        }
    }

    public static class EmailNotVerified extends StoreForgeException {
        public EmailNotVerified() {
            super(HttpStatus.FORBIDDEN, "email_not_verified", "Verify your email address before signing in.");
        }
    }

    public static class InvalidRefreshToken extends StoreForgeException {
        public InvalidRefreshToken() {
            super(HttpStatus.UNAUTHORIZED, "invalid_refresh_token", "The session has expired or was revoked. Sign in again.");
        }
    }

    public static class InvalidEmailToken extends StoreForgeException {
        public InvalidEmailToken() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_or_expired_token", "The link is invalid, expired or was already used.");
        }
    }

    public static class WeakPassword extends StoreForgeException {
        public WeakPassword(String reason) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "weak_password", reason);
        }
    }

    public static class InvalidEmail extends StoreForgeException {
        public InvalidEmail() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_email", "Not a valid email address.");
        }
    }

    public static class CrossSiteRequestRejected extends StoreForgeException {
        public CrossSiteRequestRejected() {
            super(HttpStatus.FORBIDDEN, "cross_site_request_rejected", "Request origin is not allowed.");
        }
    }
}
