package dev.storeforge.notifications;

import dev.storeforge.shared.tenancy.StoreId;
import org.jspecify.annotations.Nullable;

/**
 * Durable email: {@link #enqueueEmail} writes the message and its send job in the CALLER's
 * transaction (MANDATORY). The email exists iff the business change committed, and a job delivers
 * it with retries. Delivery is at least once (an SMTP send can't be made idempotent), but the dedup
 * key guarantees each logical email is created only once.
 */
public interface Notifications {

    /**
     * @param dedupKey  one email per key, e.g. {@code order-confirmation:<orderId>}; a second call is ignored
     * @param sensitive the body contains a secret (single-use link): it is erased once delivered
     * @return true if a new email was queued
     */
    boolean enqueueEmail(@Nullable StoreId storeId, EmailMessage message, String dedupKey, boolean sensitive);
}
