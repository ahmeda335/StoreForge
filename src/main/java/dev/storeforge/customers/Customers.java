package dev.storeforge.customers;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface Customers {

    /**
     * Finds the store's customer with this (normalized) email or creates one. Atomic under
     * concurrency (two checkouts with the same email never create two customers). Must run in the
     * caller's tenant transaction.
     */
    UUID findOrCreate(StoreId storeId, String normalizedEmail, @Nullable String firstName, @Nullable String lastName);
}
