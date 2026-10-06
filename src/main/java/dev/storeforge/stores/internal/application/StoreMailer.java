package dev.storeforge.stores.internal.application;

import dev.storeforge.notifications.EmailDeliveryException;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sends invitation emails after the inviting transaction commits (same P2 limitation: not durable until P9). */
@Component
class StoreMailer {

    private static final Logger log = LoggerFactory.getLogger(StoreMailer.class);

    private final EmailSender emailSender;

    StoreMailer(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @TransactionalEventListener
    void on(InvitationService.InvitationEmail invitation) {
        try {
            emailSender.send(new EmailMessage(invitation.to().value(),
                    "You are invited to " + invitation.storeName() + " on StoreForge",
                    "You have been invited to join " + invitation.storeName() + ".\n\n"
                            + "Sign in (or create an account with this email address), then open:\n"
                            + invitation.link()));
        } catch (EmailDeliveryException e) {
            log.atWarn().setCause(e).log("Invitation email could not be delivered; it can be re-sent by inviting again");
        }
    }
}
