package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.security.AuthProperties;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.Notifications;
import dev.storeforge.shared.ids.Ids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

/**
 * Renders identity emails and queues them DURABLY in the publishing transaction (synchronous
 * {@code @EventListener}): the email commits or rolls back with the registration/reset, and a job
 * delivers it with retries (P9; before that, a failed SMTP call lost the email, EXP-002).
 * Verification/reset bodies are marked sensitive: the link is erased from the database once sent.
 * Links carry the token in the URL FRAGMENT (after '#'): browsers never send fragments to servers,
 * so tokens don't end up in proxy or access logs.
 */
@Component
@RequiredArgsConstructor
class IdentityMailer {

    private final Notifications notifications;
    private final AuthProperties properties;

    @EventListener
    void on(IdentityEmails email) {
        EmailMessage message = switch (email) {
            case IdentityEmails.VerifyEmail verify -> new EmailMessage(verify.to().value(),
                    "Verify your StoreForge email address",
                    "Confirm your email address by opening this link (valid for "
                            + properties.emailVerificationTtl().toHours() + " hours):\n\n"
                            + properties.appBaseUrl() + "/verify-email#token=" + verify.rawToken());
            case IdentityEmails.ResetPassword reset -> new EmailMessage(reset.to().value(),
                    "Reset your StoreForge password",
                    "Choose a new password by opening this link (valid for "
                            + properties.passwordResetTtl().toMinutes() + " minutes):\n\n"
                            + properties.appBaseUrl() + "/reset-password#token=" + reset.rawToken()
                            + "\n\nIf you did not ask for this, ignore this email.");
            case IdentityEmails.AccountAlreadyExists exists -> new EmailMessage(exists.to().value(),
                    "You already have a StoreForge account",
                    "Someone (hopefully you) tried to register with this address, which already has an account.\n"
                            + "Forgot your password? " + properties.appBaseUrl() + "/forgot-password");
        };
        boolean sensitive = !(email instanceof IdentityEmails.AccountAlreadyExists);
        notifications.enqueueEmail(null, message, "identity:" + Ids.newId(), sensitive);
    }
}
