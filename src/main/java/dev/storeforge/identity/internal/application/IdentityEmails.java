package dev.storeforge.identity.internal.application;

import dev.storeforge.shared.email.EmailAddress;

/**
 * In-process events asking for an email to be sent AFTER the current transaction commits (an email
 * for a registration that rolled back would be a lie). They are not durable: a crash between commit
 * and sending loses the email, and the user must ask for a new link. P9 replaces this with the
 * transactional outbox. toString() is redacted because events carry single-use secrets.
 */
sealed interface IdentityEmails {

    EmailAddress to();

    record VerifyEmail(EmailAddress to, String rawToken) implements IdentityEmails {
        @Override
        public String toString() {
            return "VerifyEmail[REDACTED]";
        }
    }

    record ResetPassword(EmailAddress to, String rawToken) implements IdentityEmails {
        @Override
        public String toString() {
            return "ResetPassword[REDACTED]";
        }
    }

    /** Sent instead of a verification email when someone registers with an existing address. */
    record AccountAlreadyExists(EmailAddress to) implements IdentityEmails {
        @Override
        public String toString() {
            return "AccountAlreadyExists[REDACTED]";
        }
    }
}
