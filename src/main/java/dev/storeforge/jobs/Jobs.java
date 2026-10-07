package dev.storeforge.jobs;

import dev.storeforge.shared.tenancy.StoreId;
import org.jspecify.annotations.Nullable;

/** Enqueue a job for a {@link JobHandler} in the caller's transaction (MANDATORY). */
public interface Jobs {

    /** @param dedupKey a second enqueue with the same (type, dedupKey) is ignored */
    void enqueue(@Nullable StoreId storeId, String type, String dedupKey, Object payload);
}
