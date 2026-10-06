/**
 * Notifications: delivering messages (email for now) to people.
 *
 * <p>Public API: {@link dev.storeforge.notifications.EmailSender} and
 * {@link dev.storeforge.notifications.EmailMessage}. Phase 2 sends synchronously after the
 * caller's transaction commits; Phase 9 turns sending into retried, idempotent jobs fed by the
 * transactional outbox.
 */
@ApplicationModule(displayName = "Notifications")
package dev.storeforge.notifications;

import org.springframework.modulith.ApplicationModule;
