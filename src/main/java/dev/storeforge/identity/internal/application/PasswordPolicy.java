package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.domain.EmailAddress;

/**
 * NIST SP 800-63B style: length over complexity rules. The 128-char maximum is not about security
 * but cost: Argon2 work grows with input, so unbounded passwords are a cheap DoS lever.
 * (A breached-password check is planned for P14.)
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    static void check(String password, EmailAddress email) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new IdentityExceptions.WeakPassword("Password must be between 12 and 128 characters.");
        }
        if (password.equalsIgnoreCase(email.value())) {
            throw new IdentityExceptions.WeakPassword("Password must not be your email address.");
        }
    }

    static EmailAddress parseEmail(String raw) {
        try {
            return EmailAddress.of(raw);
        } catch (IllegalArgumentException e) {
            throw new IdentityExceptions.InvalidEmail();
        }
    }
}
