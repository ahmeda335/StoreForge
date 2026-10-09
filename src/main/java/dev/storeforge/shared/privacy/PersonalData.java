package dev.storeforge.shared.privacy;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Implemented by every module that stores personal data about a store's customers (ADR-027). The
 * customers module runs all implementations in ONE tenant transaction, so an export is complete
 * and an erasure is all-or-nothing, while each module keeps owning its own tables.
 */
public interface PersonalData {

    /** Name of this module's section in an export ("orders", "checkouts", ...). */
    String section();

    /** Refuse erasure while it would break something in progress (e.g. an order that still has to ship). */
    default void checkErasable(StoreId storeId, DataSubject subject) {
    }

    /** This module's data about the subject, as plain JSON-able values; null if there is nothing. */
    @Nullable Object export(StoreId storeId, DataSubject subject);

    /**
     * Replaces personal data with placeholders. Records needed for accounting (amounts, items,
     * dates, country) are kept; who and where are not.
     */
    void erase(StoreId storeId, DataSubject subject);

    /** {@code replacementEmail} is unique per customer and undeliverable ({@code .invalid}, RFC 2606). */
    record DataSubject(UUID customerId, String email, String replacementEmail) {
        @Override
        public String toString() {
            return "DataSubject[" + customerId + "]";
        }
    }
}
