package dev.storeforge.jobs;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.UUID;

/**
 * Idempotent consumers for effects that are NOT naturally idempotent (counters, sums). Call inside
 * the tenant transaction that performs the effect: the processed-message row and the effect commit
 * together, so a redelivered message finds the row and skips the effect.
 */
public interface Inbox {

    /** @return true the first time (do the work), false for a redelivery (skip) */
    boolean firstDelivery(StoreId storeId, String consumer, UUID messageId);
}
