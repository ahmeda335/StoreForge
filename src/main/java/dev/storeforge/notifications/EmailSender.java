package dev.storeforge.notifications;

/**
 * Port to an email provider. A real seam: SMTP (Mailpit locally) in the app, an in-memory
 * recorder in tests, and possibly an HTTP-API provider later.
 */
public interface EmailSender {

    /** @throws EmailDeliveryException if the provider rejected or could not be reached */
    void send(EmailMessage message);
}
