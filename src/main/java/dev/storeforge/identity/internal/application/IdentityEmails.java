package dev.storeforge.identity.internal.application;

import dev.storeforge.shared.email.EmailAddress;

/**
 * In-process events asking for an identity email. Published INSIDE the business transaction and
 * handled synchronously by {@link IdentityMailer}, which queues the email durably in that same
 * transaction. toString() is redacted because events carry single-use secrets.
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
