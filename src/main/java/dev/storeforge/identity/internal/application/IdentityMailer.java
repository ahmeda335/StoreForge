package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.security.AuthProperties;
import dev.storeforge.notifications.EmailDeliveryException;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Renders and sends identity emails after commit. Delivery failure is logged and swallowed: the
 * registration/reset itself succeeded, and the user can request a new link. Links carry the token
 * in the URL FRAGMENT (after '#'): browsers never send fragments to servers, so tokens don't end
 * up in proxy or access logs. The frontend reads it and POSTs it to the API.
 */
@Component
class IdentityMailer {

    private static final Logger log = LoggerFactory.getLogger(IdentityMailer.class);

    private final EmailSender emailSender;
    private final AuthProperties properties;

    IdentityMailer(EmailSender emailSender, AuthProperties properties) {
        this.emailSender = emailSender;
        this.properties = properties;
    }

    @TransactionalEventListener(fallbackExecution = true)
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
        try {
            emailSender.send(message);
        } catch (EmailDeliveryException e) {
            log.atWarn().addKeyValue("emailType", email.getClass().getSimpleName())
                    .setCause(e).log("Identity email could not be delivered; the user can request a new one");
        }
    }
}
