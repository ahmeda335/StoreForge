/**
 * Notifications: delivering messages (email for now) to people.
 *
 * <p>Public API: {@link dev.storeforge.notifications.Notifications} (queue an email durably in
 * the caller's transaction), {@link dev.storeforge.notifications.EmailMessage}, and the
 * {@link dev.storeforge.notifications.EmailSender} port (SMTP adapter; test double in tests).
 */
@ApplicationModule(displayName = "Notifications")
package dev.storeforge.notifications;

import org.springframework.modulith.ApplicationModule;
