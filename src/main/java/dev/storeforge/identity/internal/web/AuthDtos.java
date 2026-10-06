package dev.storeforge.identity.internal.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response bodies of the /auth API. Records with Bean Validation: syntactic checks at the edge. */
final class AuthDtos {

    private AuthDtos() {
    }

    record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {
        @Override
        public String toString() {
            return "RegisterRequest[REDACTED]";
        }
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "LoginRequest[REDACTED]";
        }
    }

    record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    record TokenRequest(@NotBlank @Size(max = 128) String token) {
        @Override
        public String toString() {
            return "TokenRequest[REDACTED]";
        }
    }

    record PasswordResetConfirmation(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
        @Override
        public String toString() {
            return "PasswordResetConfirmation[REDACTED]";
        }
    }

    /** OAuth-style token response (RFC 6749 §5.1 field names). */
    record TokenResponse(String access_token, String token_type, long expires_in) {
        @Override
        public String toString() {
            return "TokenResponse[REDACTED]";
        }
    }

    record AcceptedResponse(String message) {
    }
}
