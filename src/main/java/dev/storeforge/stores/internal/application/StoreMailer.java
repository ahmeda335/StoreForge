package dev.storeforge.stores.internal.application;

import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.Notifications;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Queues invitation emails durably INSIDE the inviting transaction (synchronous listener): no
 * invitation without its email, no email for a rolled-back invitation. The link holds a single-use
 * token, so the body is erased once sent.
 */
@Component
@RequiredArgsConstructor
class StoreMailer {

    private final Notifications notifications;

    @EventListener
    void on(InvitationService.InvitationEmail invitation) {
        notifications.enqueueEmail(invitation.storeId(), new EmailMessage(invitation.to().value(),
                        "You are invited to " + invitation.storeName() + " on StoreForge",
                        "You have been invited to join " + invitation.storeName() + ".\n\n"
                                + "Sign in (or create an account with this email address), then open:\n"
                                + invitation.link()),
                "invitation:" + invitation.invitationId(), true);
    }
}
